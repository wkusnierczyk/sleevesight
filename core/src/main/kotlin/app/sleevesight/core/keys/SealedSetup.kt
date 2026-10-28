package app.sleevesight.core.keys

import kotlin.io.encoding.Base64

// The sealed setup code (#590): the setup code's text (#268, [KeySetup]) sealed with a key derived
// from the words *Load keys* shows ([KeyWords]), so that a photo, a screenshot or a recording of
// the QR code gives nothing without them. The API keys page seals it (`assets/api-keys/seal.js`),
// as does the CLI's `keys-qr`; the phone opens it. The app's tests check the two sides agree, from
// vectors the page's code makes (`assets/api-keys/make_vectors.mjs`).

/**
 * The cryptography a sealed setup code needs, behind an interface because it is the platform's
 * (D2); the JVM's and Android's is `JvmKeyCrypto`.
 */
interface KeyCrypto {
    /** [size] random bytes, from a cryptographically strong source. */
    fun random(size: Int): ByteArray

    /** A random number from 0 up to, and not including, [bound], from the same source. */
    fun pick(bound: Int): Int

    /** PBKDF2-HMAC-SHA256 of [password] and [salt], [iterations] rounds, [SealedSetup.KEY_BYTES] long. */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray

    /** [plain] sealed with AES-GCM under [key], [nonce] and the associated data [aad]: the text, then a 16-byte tag. */
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray

    /** What [sealed] holds, opened as [seal] sealed it; null when its tag does not match: another key, or a change. */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray?
}

/** What a sealed setup code opened with some words gives ([SealedSetup.open]). */
sealed interface Opened {
    /** The setup code's [text], which [KeySetup.read] reads. */
    class Setup(
        val text: String,
    ) : Opened

    /** The code was sealed with other words, or changed since. */
    data object WrongWords : Opened

    /** Not a sealed code this app can read: cut short, not base64url, or of a rounds count out of bounds. */
    data object Damaged : Opened
}

/**
 * The sealed setup code, version 2 of the setup code: [PREFIX], then base64url without padding of
 *
 * - the PBKDF2 rounds, 4 bytes, big-endian ([ITERATIONS] as made; [ITERATIONS] to [MAX_ITERATIONS]
 *   opened, so that the count can be raised without a new version, and a code cannot hold the phone
 *   for minutes);
 * - a random salt, [SALT_BYTES];
 * - a random nonce, [NONCE_BYTES];
 * - the setup code's text ([KeySetup.write], version 1) as UTF-8, sealed with AES-256-GCM, its tag
 *   16 bytes, the associated data [PREFIX] as ASCII.
 *
 * The AES key is PBKDF2-HMAC-SHA256 of the [KeyWords.COUNT] words, in their order, in lower case and
 * separated by single spaces, as UTF-8 (the list's words are ASCII), with the salt.
 */
object SealedSetup {
    /** What a sealed code starts with: the setup code's first line's prefix, its version, and a colon. */
    const val PREFIX = KeySetup.PREFIX + "v2:"

    /** The rounds a code is made with: about a second on a low-end phone. */
    const val ITERATIONS = 600_000

    /** The most rounds a code opened may ask for. */
    const val MAX_ITERATIONS = 6_000_000

    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private const val ROUNDS_BYTES = 4
    private const val HEADER_BYTES = ROUNDS_BYTES + SALT_BYTES + NONCE_BYTES
    private const val BYTE_MASK = 0xff

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val aad = PREFIX.encodeToByteArray()

    /** Whether [text], a scanned code, is a sealed setup code, whatever it holds. */
    fun isSealed(text: String): Boolean = text.trim().startsWith(PREFIX)

    /** The password the [words] give: in lower case, separated by single spaces, as UTF-8. */
    fun password(words: List<String>): ByteArray = words.joinToString(" ") { it.lowercase() }.encodeToByteArray()

    /**
     * The sealed code of [setup], a setup code's text, under the [words], with [salt] and [nonce]
     * (random ones, unless a test gives them) and [ITERATIONS] rounds.
     */
    fun seal(
        setup: String,
        words: List<String>,
        crypto: KeyCrypto,
        salt: ByteArray = crypto.random(SALT_BYTES),
        nonce: ByteArray = crypto.random(NONCE_BYTES),
    ): String {
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "salt or nonce of the wrong size" }
        val key = crypto.derive(password(words), salt, ITERATIONS)
        val plain = setup.encodeToByteArray()
        val sealed =
            try {
                crypto.seal(key, nonce, aad, plain)
            } finally {
                key.fill(0)
                plain.fill(0)
            }
        return PREFIX + base64.encode(rounds(ITERATIONS) + salt + nonce + sealed)
    }

    /** What [code], a sealed setup code, holds, opened with the [words]. */
    fun open(
        code: String,
        words: List<String>,
        crypto: KeyCrypto,
    ): Opened {
        val bytes =
            code
                .trim()
                .takeIf { it.startsWith(PREFIX) }
                ?.let { decode(it.substring(PREFIX.length)) }
                ?.takeIf { it.size >= HEADER_BYTES + TAG_BYTES && roundsOf(it) in ITERATIONS..MAX_ITERATIONS }
        return bytes?.let { openBytes(it, words, crypto) } ?: Opened.Damaged
    }

    // What [bytes], a sealed code's, of a possible length and rounds, holds; wiped once read.
    private fun openBytes(
        bytes: ByteArray,
        words: List<String>,
        crypto: KeyCrypto,
    ): Opened {
        val salt = bytes.copyOfRange(ROUNDS_BYTES, ROUNDS_BYTES + SALT_BYTES)
        val nonce = bytes.copyOfRange(ROUNDS_BYTES + SALT_BYTES, HEADER_BYTES)
        val key = crypto.derive(password(words), salt, roundsOf(bytes))
        val plain =
            try {
                crypto.open(key, nonce, aad, bytes.copyOfRange(HEADER_BYTES, bytes.size))
            } finally {
                key.fill(0)
                bytes.fill(0)
            }
        return plain?.let(::utf8) ?: Opened.WrongWords
    }

    // [plain] as the setup code's text, or damaged when it is not UTF-8; wiped once read.
    private fun utf8(plain: ByteArray): Opened =
        try {
            Opened.Setup(plain.decodeToString(throwOnInvalidSequence = true))
        } catch (_: CharacterCodingException) {
            Opened.Damaged
        } finally {
            plain.fill(0)
        }

    private fun decode(text: String): ByteArray? =
        try {
            base64.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun rounds(iterations: Int): ByteArray =
        ByteArray(ROUNDS_BYTES) { (iterations ushr (Byte.SIZE_BITS * (ROUNDS_BYTES - 1 - it))).toByte() }

    // The rounds, read as an unsigned number; one past `Int.MAX_VALUE` reads as negative, out of bounds.
    private fun roundsOf(bytes: ByteArray): Int =
        (0 until ROUNDS_BYTES).fold(0) { value, i -> (value shl Byte.SIZE_BITS) or (bytes[i].toInt() and BYTE_MASK) }
}
