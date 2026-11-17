package app.sleevesight.app.secrets

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
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

    /** OpenRouter refused the code, or answered with no key, or the key could not be saved. */
    REFUSED,
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
 * sign-in at a time: a new one replaces the one before.
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
    /** A sign-in under way: its verifier and when it began. */
    class Pending(
        val verifier: String,
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
        pending = Pending(begun.verifier, clock.now())
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
     * approved). Ignored with no sign-in under way.
     */
    fun callback(code: String?) {
        val under = pending ?: return
        pending = null
        if (code.isNullOrBlank()) {
            now.value = SignInStep.Failed(SignInFailure.DECLINED)
            return
        }
        now.value = SignInStep.Exchanging
        scope.launch {
            now.value =
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
        /** How long OpenRouter takes a code: ten minutes from its issue. */
        val CODE_LIFETIME: Duration = 10.minutes

        /** Whether [uri] (as a string) is a sign-in's callback at [callback]. */
        fun isCallback(
            uri: String?,
            callback: String,
        ): Boolean = uri != null && (uri == callback || uri.startsWith("$callback?") || uri.startsWith("$callback/"))
    }
}

/**
 * A key from a sign-in with OpenRouter (#1157), saved in [store], and OpenRouter switched on at once
 * ([switchOn], the settings' write): true only once both are done. When the switch cannot be saved,
 * the key saved is removed again, so the screen never says "Signed in" for a sign-in that did not
 * finish, and the service on before stays on; the sign-in then says OpenRouter did not hand over the
 * key, to try again.
 */
suspend fun saveAndSwitchOn(
    store: SecretStore,
    key: Secret,
    switchOn: suspend () -> Unit,
): Boolean {
    if (store.set(SecretId.OPENROUTER, key) != SaveResult.SAVED) return false
    return try {
        switchOn()
        true
    } catch (_: IOException) {
        store.clear(SecretId.OPENROUTER)
        false
    }
}
