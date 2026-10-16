package app.sleevesight.app.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
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

    /** Forgets a key looked up earlier, so the next [key] looks it up again; deletes nothing. */
    fun forget()

    /**
     * Deletes the key, so the next [key] creates a new one. Nothing it sealed opens again: the caller
     * replaces it only when that costs nothing (see [SecretCipher.seal]).
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
     * [plain] sealed, or null when the key store fails.
     *
     * A failure is retried once with the key looked up afresh, deleting nothing: Android reports
     * transient key store errors (secure hardware busy, keystore2 restarting) as the same
     * [java.security.InvalidKeyException] or [java.security.UnrecoverableKeyException] as a key
     * that is gone. The key is replaced, and the seal tried once more, only when that loses
     * nothing: the key store says the key is invalidated for good
     * ([KeyPermanentlyInvalidatedException]), or [nothingOpens], asked only after the retry
     * failed, says no stored value can open under it ([mightOpen]; none stored counts). So
     * the user can enter a key again after the Keystore lost theirs, and a passing error does not
     * cost the other keys.
     */
    fun seal(
        plain: ByteArray,
        associated: ByteArray,
        nothingOpens: () -> Boolean = { false },
    ): ByteArray? {
        var result = attempt(plain, associated)
        if (result == Attempt.Failed) {
            keys.forget()
            result = attempt(plain, associated)
        }
        val replace = result == Attempt.Invalidated || (result == Attempt.Failed && nothingOpens())
        if (replace && replaced()) result = attempt(plain, associated)
        return (result as? Attempt.Sealed)?.value
    }

    /**
     * The plain value of [sealed], or null when it cannot be opened: a value in another format, a
     * damaged one, one sealed with another key (the Keystore key is gone, as after a restore onto
     * another phone or a reset Keystore), or a key store that fails.
     */
    fun open(
        sealed: ByteArray,
        associated: ByteArray,
    ): ByteArray? = (read(sealed, associated) as? Read.Opened)?.value

    /**
     * Whether [sealed] opens, or may open once the key store works again: false only when it is
     * shown not to, being damaged, sealed with another key, or under a key invalidated for good. A
     * key store failure is no such proof, since Android reports a passing error as it reports a key
     * that is gone; the guard of [seal] relies on that.
     */
    fun mightOpen(
        sealed: ByteArray,
        associated: ByteArray,
    ): Boolean =
        when (val read = read(sealed, associated)) {
            is Read.Opened -> {
                read.value.fill(0)
                true
            }

            Read.KeyStoreFailed -> {
                true
            }

            Read.Lost -> {
                false
            }
        }

    @Suppress("SwallowedException") // The kind of failure is all the callers act on.
    private fun read(
        sealed: ByteArray,
        associated: ByteArray,
    ): Read =
        if (sealed.size < HEADER_BYTES + TAG_BYTES || sealed[0] != VERSION) {
            Read.Lost
        } else {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
                cipher.updateAAD(associated)
                Read.Opened(cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES))
            } catch (e: AEADBadTagException) {
                // Damaged, moved from another entry, or sealed with another key.
                Read.Lost
            } catch (e: KeyPermanentlyInvalidatedException) {
                Read.Lost
            } catch (e: GeneralSecurityException) {
                Read.KeyStoreFailed
            } catch (e: ProviderException) {
                Read.KeyStoreFailed
            } catch (e: IOException) {
                // The key store could not be loaded.
                Read.KeyStoreFailed
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
        } catch (e: KeyPermanentlyInvalidatedException) {
            Attempt.Invalidated
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

    private sealed interface Read {
        class Opened(
            val value: ByteArray,
        ) : Read

        /** Shown not to open: damaged, sealed with another key, or the key is gone for good. */
        data object Lost : Read

        /** The key store failed; it may open later. */
        data object KeyStoreFailed : Read
    }

    private sealed interface Attempt {
        class Sealed(
            val value: ByteArray,
        ) : Attempt

        /** The key store says the key is gone for good. */
        data object Invalidated : Attempt

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

    override fun forget() {
        synchronized(lock) { cached = null }
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
