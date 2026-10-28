package app.sleevesight.core.keys

import app.sleevesight.core.platform.JvmKeyCrypto
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SealedSetupTest {
    private val words = listOf("aardvark", "abandoned", "abbreviate", "abdomen", "abhorrence")
    private val setup = "sleevesight-keys:v1\nlastfm=0123456789abcdef0123456789abcdef"
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `PBKDF2 is the standard one, by RFC 7914's vectors`() {
        // RFC 7914, section 11: PBKDF2-HMAC-SHA-256, the first 32 of the 64 bytes given.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            hex(JvmKeyCrypto.derive("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            hex(JvmKeyCrypto.derive("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000)),
        )
    }

    @Test
    fun `a code sealed with the words opens with them, whatever their case, and its layout is as told`() {
        val salt = ByteArray(SealedSetup.SALT_BYTES) { it.toByte() }
        val nonce = ByteArray(SealedSetup.NONCE_BYTES) { (0x40 + it).toByte() }
        val code = SealedSetup.seal(setup, words, JvmKeyCrypto, salt, nonce)
        assertTrue(code.startsWith("sleevesight-keys:v2:"))
        assertTrue(SealedSetup.isSealed(code))
        val bytes = base64.decode(code.removePrefix(SealedSetup.PREFIX))
        assertEquals("000927c0", hex(bytes.copyOfRange(0, 4)))
        assertEquals(hex(salt), hex(bytes.copyOfRange(4, 20)))
        assertEquals(hex(nonce), hex(bytes.copyOfRange(20, 32)))
        assertEquals(32 + setup.length + 16, bytes.size)

        assertEquals(setup, assertIs<Opened.Setup>(SealedSetup.open(code, words, JvmKeyCrypto)).text)
        val shouted = words.map { it.uppercase() }
        assertEquals(setup, assertIs<Opened.Setup>(SealedSetup.open(" $code\n", shouted, JvmKeyCrypto)).text)
        assertEquals(SetupRead.Sealed, KeySetup.read(code))
    }

    @Test
    fun `a wrong word, words in another order, or a changed code fail the tag`() {
        val code = SealedSetup.seal(setup, words, JvmKeyCrypto)
        assertEquals(Opened.WrongWords, SealedSetup.open(code, words.dropLast(1) + "abiding", JvmKeyCrypto))
        assertEquals(Opened.WrongWords, SealedSetup.open(code, words.reversed(), JvmKeyCrypto))
        val bytes = base64.decode(code.removePrefix(SealedSetup.PREFIX))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val changed = SealedSetup.PREFIX + base64.encode(bytes)
        assertEquals(Opened.WrongWords, SealedSetup.open(changed, words, JvmKeyCrypto))
    }

    @Test
    fun `two codes of the same keys and words differ, by a fresh salt and nonce each`() {
        val first = SealedSetup.seal(setup, words, JvmKeyCrypto)
        val second = SealedSetup.seal(setup, words, JvmKeyCrypto)
        assertTrue(first != second)
    }

    @Test
    fun `a code cut short, not base64url, or asking for rounds out of bounds is damaged`() {
        val code = SealedSetup.seal(setup, words, JvmKeyCrypto)
        assertEquals(Opened.Damaged, SealedSetup.open(code.take(SealedSetup.PREFIX.length + 40), words, JvmKeyCrypto))
        assertEquals(Opened.Damaged, SealedSetup.open(code + "!", words, JvmKeyCrypto))
        assertEquals(Opened.Damaged, SealedSetup.open(SealedSetup.PREFIX, words, JvmKeyCrypto))
        assertEquals(Opened.Damaged, SealedSetup.open("sleevesight-keys:v1\n", words, JvmKeyCrypto))
        val bytes = base64.decode(code.removePrefix(SealedSetup.PREFIX))
        for (rounds in listOf(1, SealedSetup.ITERATIONS - 1, SealedSetup.MAX_ITERATIONS + 1, -1)) {
            for (i in 0 until 4) bytes[i] = (rounds ushr (8 * (3 - i))).toByte()
            val changed = SealedSetup.PREFIX + base64.encode(bytes)
            assertEquals(Opened.Damaged, SealedSetup.open(changed, words, JvmKeyCrypto), "rounds $rounds")
        }
    }

    @Test
    fun `a salt or nonce of another size is not sealed`() {
        assertFailsWith<IllegalArgumentException> { SealedSetup.seal(setup, words, JvmKeyCrypto, salt = ByteArray(8)) }
        assertFailsWith<IllegalArgumentException> {
            SealedSetup.seal(setup, words, JvmKeyCrypto, nonce = ByteArray(16))
        }
    }

    @Test
    fun `the setup code's reader tells a sealed code apart, and names a later version without what it seals`() {
        assertEquals(SetupRead.Sealed, KeySetup.read("sleevesight-keys:v2:AAAA"))
        assertEquals(SetupRead.Sealed, KeySetup.read("sleevesight-keys:v2\nlastfm=x"))
        assertEquals(SetupRead.Unsupported("v3"), KeySetup.read("sleevesight-keys:v3:c2VjcmV0"))
        assertTrue(KeySetup.isSetup("sleevesight-keys:v2:AAAA"))
    }
}
