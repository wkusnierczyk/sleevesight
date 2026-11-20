package app.sleevesight.app.secrets

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Where a sign-in with OpenRouter is (#1157), for *AI services* to say. */
sealed interface SignInStep {
    /** None under way. */
    data object Idle : SignInStep

    /** The browser is open on OpenRouter's page: "Finish signing in in your browser." */
    data object InBrowser : SignInStep

    /** Back with a code, the key being fetched: "Getting your key from OpenRouter…" */
    data object Exchanging : SignInStep

    /** It did not finish, and [why]: one line above the button, until the next sign-in. */
    data class Failed(
        val why: SignInFailure,
    ) : SignInStep
}

/** Why a sign-in did not finish: each has its line under *AI services*. */
enum class SignInFailure {
    /** The app came back to the front with no answer: the browser was closed. */
    CLOSED,

    /** OpenRouter came back without a code: Sleevesight was not approved. */
    DECLINED,

    /** OpenRouter could not be reached to fetch the key. */
    OFFLINE,

    /** OpenRouter refused the code after its ten minutes. */
    EXPIRED,

    /**
     * OpenRouter refused the code, or answered with no key, or the key could not be saved; or its
     * answer carried another sign-in's state.
     */
    REFUSED,
}

/** How an answer at the callback stood to the sign-in under way, for the log (#1396). */
enum class StateMatch {
    /** It carried the sign-in's state back. */
    MATCHED,

    /** It carried no state, taken while one sign-in is under way: the code needs its verifier. */
    MISSING,

    /** It carried another state: not taken, and the sign-in ends as not finished. */
    DIFFERED,

    /** No sign-in was under way: ignored. */
    NO_SIGN_IN,
}

/**
 * A sign-in with OpenRouter (#1157), from the tap to the key saved: [start] makes a new verifier
 * ([KeySignIn.begin]) and gives the page to open; the activity hands back what comes to the
 * callback ([callback]), or that it is in front again with no answer ([resumed]), which means the
 * browser was closed. With a code, the key is fetched ([KeySignIn.exchange]) and handed to [save],
 * which stores it and switches OpenRouter on.
 *
 * The verifier is kept only here, in memory, and in the activity's saved state ([pending],
 * [restore]), so a process ended while the browser is open still finishes; it is dropped once a code
 * comes or the sign-in fails, so a code sent again, or with no sign-in under way, is ignored. One
 * sign-in at a time: a new one replaces the one before. An answer with no state is taken for the one
 * under way (#1396: the redirect may drop the callback's own query): with PKCE, a code is of no use
 * without the verifier, which never leaves the phone but to OpenRouter.
 *
 * @param signIn OpenRouter's sign-in.
 * @param save stores the key and switches OpenRouter on; false when the key could not be stored.
 * @param scope where the exchange runs, the process's.
 * @param clock to tell a code refused after its ten minutes ([CODE_LIFETIME]) from one refused.
 */
class OpenRouterLogin(
    private val signIn: KeySignIn,
    private val save: suspend (Secret) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    /** A sign-in under way: its verifier, its state (which its answer carries back), and when it began. */
    class Pending(
        val verifier: String,
        val state: String,
        val started: Instant,
    ) {
        override fun toString(): String = "Pending(started=$started)"
    }

    private val now = MutableStateFlow<SignInStep>(SignInStep.Idle)

    /** Where the sign-in is. */
    val step: StateFlow<SignInStep> = now.asStateFlow()

    /** The sign-in under way, for the activity's saved state; null when none waits for a code. */
    @Volatile
    var pending: Pending? = null
        private set

    /** The callback OpenRouter sends the code to, which the activity takes. */
    val callback: String get() = signIn.callback

    /** Begins a sign-in: the page to open in the browser. */
    fun start(): String {
        val begun = signIn.begin()
        pending = Pending(begun.verifier, begun.state, clock.now())
        now.value = SignInStep.InBrowser
        return begun.url
    }

    /** The browser could not be opened: nothing began. */
    fun notOpened() {
        pending = null
        now.value = SignInStep.Idle
    }

    /**
     * The sign-in [saved] in the activity's state, after the process was ended; kept only when none
     * is under way here, which is newer.
     */
    fun restore(saved: Pending) {
        if (pending == null && now.value == SignInStep.Idle) {
            pending = saved
            now.value = SignInStep.InBrowser
        }
    }

    /**
     * What came to the callback: a [code], or none (OpenRouter's page sent back no code: not
     * approved), with the [state] it carried back, or none. Ignored with no sign-in under way. With
     * the sign-in's state, or none (#1396), it is the sign-in's answer. With another state it is not
     * taken, and the sign-in ends as not finished ([SignInFailure.REFUSED]), not as closed: an answer
     * did come, and the browser, its authorisation spent, has nothing more to send.
     *
     * @return how the answer stood to the sign-in under way, for the log.
     */
    fun callback(
        code: String?,
        state: String?,
    ): StateMatch {
        val under = pending ?: return StateMatch.NO_SIGN_IN
        val match =
            when {
                state.isNullOrEmpty() -> StateMatch.MISSING
                state == under.state -> StateMatch.MATCHED
                else -> StateMatch.DIFFERED
            }
        pending = null
        when {
            match == StateMatch.DIFFERED -> now.value = SignInStep.Failed(SignInFailure.REFUSED)
            code.isNullOrBlank() -> now.value = SignInStep.Failed(SignInFailure.DECLINED)
            else -> exchange(code, under)
        }
        return match
    }

    // The key for [code] fetched and saved, for the sign-in [under] way.
    @Suppress("TooGenericExceptionCaught") // Any failure ends the sign-in as one not finished.
    private fun exchange(
        code: String,
        under: Pending,
    ) {
        now.value = SignInStep.Exchanging
        scope.launch {
            now.value =
                try {
                    finish(code, under)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Whatever else fails, the sign-in did not finish: it says so, and can be tried again.
                    SignInStep.Failed(SignInFailure.REFUSED)
                }
        }
    }

    // The key for [code], saved: where the sign-in [under] way ends.
    private suspend fun finish(
        code: String,
        under: Pending,
    ): SignInStep =
        when (val exchange = signIn.exchange(code, under.verifier)) {
            is KeyExchange.Key -> {
                if (save(exchange.key)) SignInStep.Idle else SignInStep.Failed(SignInFailure.REFUSED)
            }

            KeyExchange.Offline -> {
                SignInStep.Failed(SignInFailure.OFFLINE)
            }

            KeyExchange.Refused -> {
                val late = clock.now() - under.started >= CODE_LIFETIME
                SignInStep.Failed(if (late) SignInFailure.EXPIRED else SignInFailure.REFUSED)
            }
        }

    /**
     * The app is in front again. With the browser still open for a sign-in and no code come, it was
     * closed: the sign-in did not finish.
     */
    fun resumed() {
        if (now.value == SignInStep.InBrowser) {
            pending = null
            now.value = SignInStep.Failed(SignInFailure.CLOSED)
        }
    }

    /** The line of a sign-in that did not finish is read: gone. */
    fun dismiss() {
        if (now.value is SignInStep.Failed) now.value = SignInStep.Idle
    }

    companion object {
        /** The callback's parameter that carries a sign-in's state back (`OpenRouterSignIn.STATE`). */
        const val STATE: String = "s"

        /** How long OpenRouter takes a code: ten minutes from its issue. */
        val CODE_LIFETIME: Duration = 10.minutes

        /**
         * The parameters of a callback's [query] (`s=…&code=…`), names and values percent-decoded;
         * split at `?` too, should OpenRouter add its own `?code=` after the state the callback
         * already carries. A part with no `=` that decodes to parameters (`s%3D…%26code%3D…`, the
         * query encoded once more) is read as these. The first of a name counts.
         */
        fun answerOf(query: String?): Map<String, String> {
            val answer = LinkedHashMap<String, String>()

            fun read(
                text: String,
                again: Boolean,
            ) {
                text.split('&', '?').forEach { part ->
                    if ('=' in part) {
                        val name = decoded(part.substringBefore('='))
                        if (name !in answer) answer[name] = decoded(part.substringAfter('='))
                    } else if (again) {
                        decoded(part).takeIf { it != part && '=' in it }?.let { read(it, again = false) }
                    }
                }
            }
            read(query.orEmpty(), again = true)
            return answer
        }

        /**
         * The answer at a callback [uri] (as a string): the parameters of its query, and of its
         * fragment, which OpenRouter may use instead (#1396).
         */
        fun answerAt(uri: String): Answer {
            val beforeFragment = uri.substringBefore('#')
            return Answer(
                query = answerOf(beforeFragment.substringAfter('?', "")),
                fragment = answerOf(uri.substringAfter('#', "")),
            )
        }

        // [text] percent-decoded as UTF-8 (`+` kept as it is), or as given when a `%` is not followed
        // by two ASCII hex digits or the bytes are not well-formed UTF-8. Text between escapes is
        // encoded a span at a time, so a character outside the BMP (a surrogate pair) stays whole.
        private fun decoded(text: String): String {
            val bytes = ByteArrayOutputStream(text.length)
            var at = 0
            var wellFormed = true
            while (at < text.length && wellFormed) {
                val escape = text.indexOf('%', at).takeIf { it >= 0 } ?: text.length
                val high = hexDigit(text.getOrNull(at + 1))
                val low = hexDigit(text.getOrNull(at + 2))
                when {
                    escape > at -> {
                        bytes.write(text.substring(at, escape).encodeToByteArray())
                        at = escape
                    }

                    high == null || low == null -> {
                        wellFormed = false
                    }

                    else -> {
                        bytes.write(high * HEX + low)
                        at += PERCENT_ESCAPE
                    }
                }
            }
            return bytes.toByteArray().takeIf { wellFormed }?.let(::utf8) ?: text
        }

        // [char]'s value as an ASCII hex digit, or null (not `４`, which `digitToIntOrNull` takes).
        private fun hexDigit(char: Char?): Int? =
            char?.takeIf { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }?.digitToInt(HEX)

        // [bytes] as UTF-8, or null when they are not well formed.
        private fun utf8(bytes: ByteArray): String? =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                null
            }

        // Hex digits, and the length of an escape (`%2F`).
        private const val HEX = 16
        private const val PERCENT_ESCAPE = 3

        /**
         * Whether [uri] (as a string) is a sign-in's callback at [callback]: the same address up to
         * its query or fragment, whatever these hold (#1396: `https://sleevesight.app/auth/openrouter/`,
         * so not another page of the site, nor the same path on another host).
         */
        fun isCallback(
            uri: String?,
            callback: String,
        ): Boolean = uri != null && uri.substringBefore('?').substringBefore('#') == callback
    }
}

/**
 * What came to a sign-in's callback (#1396): the parameters of its [query] and of its [fragment].
 * Shown ([toString], for the log) by the names it expects only ([NAMES]), with a count of any
 * others, never by values: the code and the state stay out of the log, and so does a name made of
 * decoded text (`s%3D…%26code%3D…=`), which could carry them.
 */
class Answer(
    val query: Map<String, String>,
    val fragment: Map<String, String>,
) {
    /** The parameter [name]: from the query, or failing that from the fragment. */
    operator fun get(name: String): String? = query[name] ?: fragment[name]

    override fun toString(): String = "query ${names(query)}, fragment ${names(fragment)}"

    private companion object {
        // The names shown: the callback's state, OpenRouter's code, and what an OAuth answer may add.
        val NAMES = listOf(OpenRouterLogin.STATE, "code", "state", "error", "error_description")

        // [parameters]' expected names, in their order, and how many others came: `[s, code, 1 other]`.
        fun names(parameters: Map<String, String>): String {
            val known = parameters.keys.filter { it in NAMES }
            val others = (parameters.size - known.size).takeIf { it > 0 }?.let { "$it other" }
            return (known + listOfNotNull(others)).joinToString(prefix = "[", postfix = "]")
        }
    }
}

/**
 * A key from a sign-in with OpenRouter (#1157), saved in [store], and OpenRouter switched on at once
 * ([switchOn], the settings' write): true only once both are done. When the switch cannot be saved,
 * the key saved is removed again, so the screen never says "Signed in" for a sign-in that did not
 * finish, and the service on before stays on; the sign-in then says OpenRouter did not hand over the
 * key, to try again.
 */
@Suppress("TooGenericExceptionCaught") // Any failure of the switch ends the sign-in as one not finished.
suspend fun saveAndSwitchOn(
    store: SecretStore,
    key: Secret,
    switchOn: suspend () -> Unit,
): Boolean {
    if (store.set(SecretId.OPENROUTER, key) != SaveResult.SAVED) return false
    return try {
        switchOn()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Whatever stops the switch (storage, or anything else), the sign-in did not finish.
        store.clear(SecretId.OPENROUTER)
        false
    }
}
