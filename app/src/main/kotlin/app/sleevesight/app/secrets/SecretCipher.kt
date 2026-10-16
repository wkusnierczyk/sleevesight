package app.sleevesight.app.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
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
     * The key, created on first use. Throws a [GeneralSecurityException], [ProviderException] or
     * [IOException] when the key store fails.
     */
    fun key(): SecretKey

    /**
     * Drops a key that can no longer be used, so the next [key] creates a new one. What it sealed
     * cannot be opened by the new key; it could not be opened by the old one either.
     */
    fun replace()
}

/**
 * Seals and opens the stored keys with AES-GCM (architecture, *Settings and secrets*), with the AES
 * key from [keys]. A sealed value is a version byte, the 12-byte IV the cipher chose, and the
 * ciphertext with its 16-byte tag. The caller's associated data is authenticated with it, so a value
 * moved to another entry does not open there. Neither function throws for a failing key store, and
 * neither logs.
 */
class SecretCipher(
    private val keys: SecretKeySource,
) {
    /**
     * [plain] sealed, or null when the key store fails. A key the key store reports unusable (an
     * [InvalidKeyException], which covers a key invalidated for good, or an
     * [UnrecoverableKeyException]) is replaced and the seal tried once more, so the user can enter a
     * key again after the Keystore lost theirs. Any other failure leaves the key alone: it may pass,
     * and the key still opens the other entries.
     */
    fun seal(
        plain: ByteArray,
        associated: ByteArray,
    ): ByteArray? =
        when (val first = attempt(plain, associated)) {
            is Attempt.Sealed -> first.value
            Attempt.Failed -> null
            Attempt.KeyUnusable -> if (replaced()) (attempt(plain, associated) as? Attempt.Sealed)?.value else null
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

    @Suppress("SwallowedException") // The kind of failure is all the caller acts on.
    private fun attempt(
        plain: ByteArray,
        associated: ByteArray,
    ): Attempt =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // The IV is the cipher's: a Keystore key with randomized encryption refuses one from outside.
            cipher.init(Cipher.ENCRYPT_MODE, keys.key())
            cipher.updateAAD(associated)
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
            Attempt.Sealed(byteArrayOf(VERSION) + iv + cipher.doFinal(plain))
        } catch (e: InvalidKeyException) {
            Attempt.KeyUnusable
        } catch (e: UnrecoverableKeyException) {
            Attempt.KeyUnusable
        } catch (e: GeneralSecurityException) {
            Attempt.Failed
        } catch (e: ProviderException) {
            Attempt.Failed
        } catch (e: IOException) {
            Attempt.Failed
        }

    @Suppress("SwallowedException") // A key that cannot be replaced means the seal failed; nothing more.
    private fun replaced(): Boolean =
        try {
            keys.replace()
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: ProviderException) {
            false
        } catch (e: IOException) {
            false
        }

    private sealed interface Attempt {
        class Sealed(
            val value: ByteArray,
        ) : Attempt

        data object KeyUnusable : Attempt

        data object Failed : Attempt
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
 *
 * The key is looked up once and kept, so a seal or an open does not load the key store again.
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

    override fun replace() {
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
