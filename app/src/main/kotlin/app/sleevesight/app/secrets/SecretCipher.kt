package app.sleevesight.app.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals and opens the stored keys with AES-GCM (architecture, *Settings and secrets*). The AES key
 * comes from [key]: in the app, [AndroidKeystoreKey], which never leaves the Keystore; in tests, a
 * software key, so the same code is tested on the JVM, where there is no Keystore.
 *
 * A sealed value is a version byte, the 12-byte IV the cipher chose, and the ciphertext with its
 * 16-byte tag. The caller's [associated data][seal] is authenticated with it, so a value moved to
 * another entry does not open there.
 */
class SecretCipher(
    private val key: () -> SecretKey,
) {
    fun seal(
        plain: ByteArray,
        associated: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The IV is the cipher's: a Keystore key with randomized encryption refuses one from outside.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associated)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    /**
     * The plain value of [sealed], or null when it cannot be opened: a value in another format, a
     * damaged one, one sealed with another key (the Keystore key is gone, as after a restore onto
     * another phone or a reset Keystore), or a Keystore that fails.
     */
    @Suppress("SwallowedException") // Every such failure means the same to the caller: unreadable.
    fun open(
        sealed: ByteArray,
        associated: ByteArray,
    ): ByteArray? {
        if (sealed.size < HEADER_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
            cipher.updateAAD(associated)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            // The Keystore's own failures, such as a key it can no longer use.
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
    }
}

/**
 * The app's AES key in the Android Keystore, created on first use: 256 bits, GCM only, no padding,
 * randomized encryption (the Keystore's default), and no user authentication, so the keys can be
 * read while the phone is locked, as the session layer may need in the background (D6). The key
 * cannot be exported; it goes when the app is uninstalled or its data is cleared, and is not part of
 * any backup, so a stored value it sealed cannot be opened anywhere else.
 */
class AndroidKeystoreKey(
    private val alias: String = ALIAS,
) : () -> SecretKey {
    private val lock = Any()

    override fun invoke(): SecretKey =
        synchronized(lock) {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey) ?: generate()
        }

    private fun generate(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "sleevesight.secrets.v1"
        const val KEY_BITS = 256
    }
}
