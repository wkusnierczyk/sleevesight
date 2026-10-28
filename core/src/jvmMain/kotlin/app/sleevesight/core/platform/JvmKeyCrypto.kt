package app.sleevesight.core.platform

import app.sleevesight.core.keys.KeyCrypto
import app.sleevesight.core.keys.SealedSetup
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [KeyCrypto] on the JVM and Android, through the platform's providers: `SecureRandom`,
 * `PBKDF2WithHmacSHA256` (Android from API 26, the app's `minSdk`) and `AES/GCM/NoPadding`. The
 * password reaches PBKDF2 as characters, which both encode as UTF-8; a sealed code's password is
 * ASCII ([SealedSetup.password]), where every encoding agrees.
 */
object JvmKeyCrypto : KeyCrypto {
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    override fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun pick(bound: Int): Int = random.nextInt(bound)

    override fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        // ASCII bytes are the non-negative ones, each its character.
        require(password.all { it >= 0 }) { "The password is not ASCII" }
        val chars = CharArray(password.size) { password[it].toInt().toChar() }
        val spec = PBEKeySpec(chars, salt, iterations, SealedSetup.KEY_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray? =
        try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
        } catch (_: GeneralSecurityException) {
            null
        }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
}
