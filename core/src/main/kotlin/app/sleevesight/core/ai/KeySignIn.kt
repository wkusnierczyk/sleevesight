package app.sleevesight.core.ai

import app.sleevesight.core.Secret

/**
 * A sign-in that gets an AI service's key for the user's own account (#1157, OpenRouter's OAuth with
 * PKCE): [begin] makes a new verifier and the page to open in the browser; the service sends the
 * app a one-time code on [callback], which [exchange] trades, with the verifier, for the key. The
 * verifier never leaves the phone but in that exchange.
 */
interface KeySignIn {
    /** Where the service sends the code back to the app: an App Link on the app's site. */
    val callback: String

    /** A new sign-in: its verifier and its state, made for it alone, and the page to open. */
    fun begin(): SignInStart

    /** The key for [code], with the [verifier] of the sign-in that asked for it; or why there is none. */
    suspend fun exchange(
        code: String,
        verifier: String,
    ): KeyExchange
}

/**
 * A sign-in begun ([KeySignIn.begin]): the [verifier] to keep; its [state], a random value the
 * callback carries back (`s`), so an answer is taken only for the sign-in that asked for it; and the
 * page to open ([url]).
 */
class SignInStart(
    val verifier: String,
    val state: String,
    val url: String,
) {
    /** Never the verifier. */
    override fun toString(): String = "SignInStart(url=$url)"
}

/** How an exchange of a code for a key ended ([KeySignIn.exchange]). */
sealed interface KeyExchange {
    /** The key, of the user's own account. */
    class Key(
        val key: Secret,
    ) : KeyExchange

    /** The phone could not reach the service. */
    data object Offline : KeyExchange

    /**
     * The service did not hand over a key: it refused the code or the verifier (a code is good for
     * ten minutes), or answered with no key.
     */
    data object Refused : KeyExchange
}
