package app.sleevesight.cli

import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.platform.JvmKeyCrypto
import java.security.Provider
import java.security.Security
import java.security.spec.KeySpec
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:core`'s JVM cryptography for the sealed setup code ([JvmKeyCrypto]), tested here because
 * `:core`'s tests may not use JVM APIs (`config/detekt/pipeline.yml`), and what the provider is
 * handed can only be seen through one (`design/mutation-testing.md`).
 */
class JvmKeyCryptoTest {
    private val salt = "salt".encodeToByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** PBKDF2 through the JDK's own provider, keeping each spec it is given. */
    private class Spy : SecretKeyFactorySpi() {
        private val real = SecretKeyFactory.getInstance(PBKDF2, "SunJCE")

        override fun engineGenerateSecret(spec: KeySpec): SecretKey = real.generateSecret(spec).also { specs += spec }

        override fun engineGetKeySpec(
            key: SecretKey,
            spec: Class<*>,
        ): KeySpec = real.getKeySpec(key, spec)

        override fun engineTranslateKey(key: SecretKey): SecretKey = real.translateKey(key)
    }

    /** A provider ahead of the JDK's, giving [Spy] for PBKDF2. */
    private class Spying : Provider(NAME, "1", "Keeps the specs PBKDF2 is given") {
        init {
            putService(
                object : Service(this, "SecretKeyFactory", PBKDF2, Spy::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = Spy()
                },
            )
        }
    }

    @Test
    fun `the password handed to PBKDF2 is cleared once the key is derived`() {
        specs.clear()
        Security.insertProviderAt(Spying(), 1)
        val key =
            try {
                JvmKeyCrypto.derive("passwd".encodeToByteArray(), salt, 1)
            } finally {
                Security.removeProvider(NAME)
            }
        // RFC 7914, section 11: the spy derived the key, through the JDK's PBKDF2.
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(key))
        val spec = assertIs<PBEKeySpec>(specs.single())
        assertFailsWith<IllegalStateException> { spec.password }
    }

    @Test
    fun `the password must be ASCII, NUL included`() {
        assertFailsWith<IllegalArgumentException> { JvmKeyCrypto.derive("caf\u00e9".encodeToByteArray(), salt, 1) }
        assertEquals(SealedSetup.KEY_BYTES, JvmKeyCrypto.derive(byteArrayOf(0), salt, 1).size)
    }

    @Test
    fun `a sealed text opens only with the associated data it was sealed with`() {
        val key = ByteArray(SealedSetup.KEY_BYTES) { it.toByte() }
        val nonce = ByteArray(SealedSetup.NONCE_BYTES)
        val text = "sleevesight-keys:v1".encodeToByteArray()
        val sealed = JvmKeyCrypto.seal(key, nonce, "a".encodeToByteArray(), text)
        assertEquals(hex(text), JvmKeyCrypto.open(key, nonce, "a".encodeToByteArray(), sealed)?.let(::hex))
        assertNull(JvmKeyCrypto.open(key, nonce, "b".encodeToByteArray(), sealed))
    }

    @Test
    fun `random bytes are of the size asked for, and differ from one call to the next`() {
        val first = JvmKeyCrypto.random(SealedSetup.SALT_BYTES)
        val second = JvmKeyCrypto.random(SealedSetup.SALT_BYTES)
        assertEquals(SealedSetup.SALT_BYTES, first.size)
        assertTrue(hex(first) != hex(second))
    }

    @Test
    fun `a pick is below its bound, and not always the same`() {
        val picks = List(200) { JvmKeyCrypto.pick(1000) }
        assertTrue(picks.all { it in 0 until 1000 })
        assertTrue(picks.toSet().size > 1)
    }

    private companion object {
        const val PBKDF2 = "PBKDF2WithHmacSHA256"
        const val NAME = "SleevesightSpy"

        /** The specs [Spy] was given. */
        val specs = mutableListOf<KeySpec>()
    }
}
