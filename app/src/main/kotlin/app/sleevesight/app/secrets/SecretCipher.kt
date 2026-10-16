package app.sleevesight.app.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where [SecretCipher] gets its AES key: in the app, [AndroidKeystoreKey]; in tests, a software key,
 * so the same code is tested on the JVM, where there is no Keystore.
 */
interface SecretKeySource {
    /**
     * The key, created only when none exists (first use, or after the Keystore was reset). Throws a
     * [GeneralSecurityException], [ProviderException] or [IOException] when the key store fails.
     */
    fun key(): SecretKey

    /** Forgets a key looked up earlier, so the next [key] looks it up again; deletes nothing. */
    fun forget()

    /**
     * Deletes the key, so the next [key] creates a new one and nothing sealed before opens again.
     * Only the user's *Reset saved keys* does this, never a failure.
     */
    fun delete()
}

/**
 * Seals and opens the stored keys with AES-GCM (architecture, *Settings and secrets*), with the AES
 * key from [keys]. A sealed value is a version byte, the 12-byte IV the cipher chose, and the
 * ciphertext with its 16-byte tag. The caller's associated data is authenticated with it, so a value
 * moved to another entry does not open there. No function throws for a failing key store, and none
 * logs.
 *
 * A failure never deletes the key: Android reports a passing key store error (secure hardware busy,
 * keystore2 restarting) with the same exceptions as a key that is gone, so no failure proves that
 * deleting it would lose nothing.
 */
class SecretCipher(
    private val keys: SecretKeySource,
) {
    /**
     * [plain] sealed, or null when the key store fails. A failure is tried once more with the key
     * looked up afresh, which gets past a key object the key store no longer knows; nothing is
     * deleted either way.
     */
    fun seal(
        plain: ByteArray,
        associated: ByteArray,
    ): ByteArray? =
        attempt(plain, associated) ?: run {
            keys.forget()
            attempt(plain, associated)
        }

    /**
     * The plain value of [sealed], or null when it cannot be opened: a value in another format, a
     * damaged one, one sealed with another key (the Keystore key is gone, as after a restore onto
     * another phone or a reset Keystore), or a key store that fails.
     */
    @Suppress("SwallowedException") // Every such failure means the same to the caller: unreadable.
    fun open(
        sealed: ByteArray,
        associated: ByteArray,
    ): ByteArray? {
        if (sealed.size < HEADER_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
            cipher.updateAAD(associated)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        } catch (e: IOException) {
            // The key store could not be loaded.
            null
        }
    }

    /** Deletes the key ([SecretKeySource.delete]); false when the key store failed. */
    @Suppress("SwallowedException") // Reported as false; nothing of the exception is needed.
    fun deleteKey(): Boolean =
        try {
            keys.delete()
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: ProviderException) {
            false
        } catch (e: IOException) {
            false
        }

    @Suppress("SwallowedException") // A failed seal is reported as null.
    private fun attempt(
        plain: ByteArray,
        associated: ByteArray,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // The IV is the cipher's: a Keystore key with randomized encryption refuses one from outside.
            cipher.init(Cipher.ENCRYPT_MODE, keys.key())
            cipher.updateAAD(associated)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
            byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        } catch (e: IOException) {
            null
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
 * The app's AES key in the Android Keystore: 256 bits, GCM only, no padding, randomized encryption
 * (the Keystore's default), and no user authentication, so the keys can be read while the phone is
 * locked, as the session layer may need in the background (D6). The key cannot be exported; it goes
 * when the app is uninstalled or its data is cleared, and is not part of any backup, so a stored
 * value it sealed cannot be opened anywhere else.
 *
 * A key is generated only when the Keystore has none under the alias; one the Keystore fails to load
 * is a failure, never a reason to make another. The key is looked up once and kept, so a seal or an
 * open does not load the key store again.
 */
class AndroidKeystoreKey(
    private val alias: String = ALIAS,
) : SecretKeySource {
    private val lock = Any()
    private var cached: SecretKey? = null

    override fun key(): SecretKey =
        synchronized(lock) {
            cached ?: ((keyStore().getKey(alias, null) as? SecretKey) ?: generate()).also { cached = it }
        }

    override fun forget() {
        synchronized(lock) { cached = null }
    }

    override fun delete() {
        synchronized(lock) {
            cached = null
            keyStore().deleteEntry(alias)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

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
