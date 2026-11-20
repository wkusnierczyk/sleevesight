package app.sleevesight.app.secrets

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import kotlin.time.Instant

/**
 * The main activity's part in a sign-in with OpenRouter (#1157), kept out of the activity itself:
 * OpenRouter's answer at the callback, when the activity starts with it or gets it as a new intent
 * (`singleTop`); the sign-in under way, kept in the activity's saved state and given back after the
 * process was ended; and the activity back in front, with no answer, which means the browser was
 * closed. Only for [ComponentActivity]s that [handles] says take part (the main activity).
 *
 * @param login the process's sign-in, read only once an activity takes part.
 */
class SignInCallbacks(
    private val handles: (Activity) -> Boolean,
    private val login: () -> OpenRouterLogin,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (!handles(activity) || activity !is ComponentActivity) return
        val login = login()
        savedInstanceState?.let { restore(login, it) }
        activity.addOnNewIntentListener { take(login, it) }
        // A start with saved state was given its intent before: the code, if any, was taken then.
        if (savedInstanceState == null) take(login, activity.intent)
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {
        if (!handles(activity)) return
        login().pending?.let { pending ->
            outState.putString(VERIFIER, pending.verifier)
            outState.putString(STATE, pending.state)
            outState.putLong(STARTED, pending.started.toEpochMilliseconds())
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (handles(activity)) login().resumed()
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun restore(
        login: OpenRouterLogin,
        saved: Bundle,
    ) {
        val verifier = saved.getString(VERIFIER) ?: return
        val state = saved.getString(STATE) ?: return
        login.restore(OpenRouterLogin.Pending(verifier, state, Instant.fromEpochMilliseconds(saved.getLong(STARTED))))
    }

    // [intent], if it is OpenRouter's answer at the sign-in's callback: its code, or none, with the
    // state it carried back. Not an intent the activity was started with again from Recents, which
    // still holds an answer taken before.
    private fun take(
        login: OpenRouterLogin,
        intent: Intent?,
    ) {
        val data =
            intent
                ?.takeIf { it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }
                ?.data
                ?.takeIf { OpenRouterLogin.isCallback(it.toString(), login.callback) }
                ?: return
        val answer = OpenRouterLogin.answerAt(data.toString())
        val match =
            login.callback(answer[CODE], answer[OpenRouterLogin.STATE], answer[OpenRouterLogin.OPENROUTER_STATE])
        // Which parameters came, and how the state stood: names only, never the code or the state.
        Log.i(TAG, logLine(answer, match))
    }

    internal companion object {
        // The app's log tag.
        const val TAG = "Sleevesight"

        /** The log line for an [answer] at the callback, and how its state stood ([match]). */
        fun logLine(
            answer: Answer,
            match: StateMatch,
        ): String = "OpenRouter sign-in answer: $answer; state ${match.name.lowercase()}"

        // A sign-in under way, in the saved state: its verifier and when it began.
        private const val VERIFIER = "openrouter.verifier"
        private const val STARTED = "openrouter.started"
        private const val STATE = "openrouter.state"

        // The code in OpenRouter's answer.
        private const val CODE = "code"
    }
}
