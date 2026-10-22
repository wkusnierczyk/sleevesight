package app.sleevesight.app.keyboard

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A session of *Type on a computer* (#394), read from the QR code that `sleevesight.app/keyboard.html`
 * shows: its id, which names the phone's Bluetooth service, and the key its messages are sealed
 * with. The page's side is `assets/keyboard/protocol.js`, which describes the format.
 *
 * [open] takes a message only if its counter is above the last one taken, so a message recorded
 * over the air and sent again is refused. A session is run once ([SessionLedger]), so that the
 * same code scanned again, which would start the counter over, cannot have those recorded messages
 * replayed: a new session means a reloaded page. [forget] wipes the key: the session ends with the
 * dialog.
 * Bluetooth's callbacks call it from their own threads, hence the locking.
 */
class KeyboardSession private constructor(
    private val id: ByteArray,
    private val key: ByteArray,
) {
    private var last = 0L
    private var forgotten = false

    /** The service the phone advertises, which the page's device chooser filters on. */
    val serviceUuid: UUID = serviceOf(id)

    /**
     * The text [message] carries, or null if it is refused: malformed, tampered, replayed, or too
     * long.
     */
    @Synchronized
    fun open(message: ByteArray): String? {
        val counter = counterOf(message)?.takeIf { !forgotten && it > last }
        val text = counter?.let { decrypt(message) }?.let(::utf8)
        if (text != null && counter != null) last = counter
        return text
    }

    // The message's text, still UTF-8, or null if it is not sealed under this session's key.
    private fun decrypt(message: ByteArray): ByteArray? =
        try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                val nonce = id + message.copyOfRange(0, COUNTER_BYTES)
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(PREFIX.toByteArray(Charsets.US_ASCII) + id)
                doFinal(message, COUNTER_BYTES, message.size - COUNTER_BYTES)
            }
        } catch (_: GeneralSecurityException) {
            null
        }

    /** Wipes the key; every message is refused from then on. */
    @Synchronized
    fun forget() {
        key.fill(0)
        forgotten = true
    }

    companion object {
        /** What a session's code starts with; the page's `PREFIX`. */
        const val PREFIX = "sleevesight-keyboard:v1:"

        /** The one characteristic the page writes to, the same in every session. */
        val CHARACTERISTIC: UUID = UUID.fromString("5d3e9a7c-1f42-4b8e-a6d1-3c0b7e52f901")

        /** The most UTF-8 a message carries: one write, 512 bytes, less the counter and the tag. */
        const val MAX_TEXT_BYTES = 480

        private const val SERVICE_PREFIX = "5d3e9a7c-1f42-4b8e-"
        private const val ID_BYTES = 8
        private const val KEY_BYTES = 16
        private const val COUNTER_BYTES = 4
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * Byte.SIZE_BITS
        private const val UNSIGNED_INT = 0xffffffffL
        private const val UUID_GROUP = 4
        private const val MAX_MESSAGE_BYTES = COUNTER_BYTES + MAX_TEXT_BYTES + TAG_BYTES

        /** The session [code] holds, or null if it is not a session's code (of this version). */
        fun parse(code: String): KeyboardSession? {
            val bytes = code.takeIf { it.startsWith(PREFIX) }?.let { base64url(it.substring(PREFIX.length)) }
            val session =
                bytes?.takeIf { it.size == ID_BYTES + KEY_BYTES }?.let {
                    KeyboardSession(it.copyOfRange(0, ID_BYTES), it.copyOfRange(ID_BYTES, it.size))
                }
            bytes?.fill(0)
            return session
        }

        /** Whether [code] claims to be a session's code, of this version or another. */
        fun isSessionCode(code: String): Boolean = code.startsWith("sleevesight-keyboard:")

        private fun serviceOf(id: ByteArray): UUID {
            val hex = id.joinToString("") { "%02x".format(it) }
            return UUID.fromString("$SERVICE_PREFIX${hex.substring(0, UUID_GROUP)}-${hex.substring(UUID_GROUP)}")
        }

        private fun base64url(text: String): ByteArray? =
            try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                null
            }

        // The counter of a message of a possible length, or null.
        private fun counterOf(message: ByteArray): Long? =
            message.takeIf { it.size in COUNTER_BYTES + TAG_BYTES..MAX_MESSAGE_BYTES }?.let {
                ByteBuffer.wrap(it, 0, COUNTER_BYTES).int.toLong() and UNSIGNED_INT
            }

        private fun utf8(bytes: ByteArray): String? =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            } finally {
                bytes.fill(0)
            }
    }
}
