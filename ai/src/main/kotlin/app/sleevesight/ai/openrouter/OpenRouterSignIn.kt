package app.sleevesight.ai.openrouter

import app.sleevesight.ai.decode
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.SignInStart
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.net.NetBody
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * Signing in with OpenRouter (#1157), its OAuth flow with PKCE
 * (https://openrouter.ai/docs/guides/overview/auth/oauth): [begin] makes a new `code_verifier` of
 * [VERIFIER_BYTES] random bytes and a state of [STATE_BYTES], and the page to open, `/auth` with the
 * `callback_url` (the callback with the state, `?s=`, which OpenRouter's answer carries back), the
 * `code_challenge` (the verifier's SHA-256, base64url) and `code_challenge_method=S256`, and
 * `key_label` naming the key on the user's account; [exchange] trades the code OpenRouter sends to
 * the callback, with the verifier, for the key (`POST /api/v1/auth/keys`). The verifier goes only
 * into that request's body, which `:net` never logs; nor does it log the answer, which holds the key.
 *
 * The callback is the app's own scheme ([CALLBACK]): an `https://` App Link would need the site to
 * vouch for the app (`assetlinks.json`), and a page of its own. A code sent to another app that
 * claimed the scheme is no use without the verifier, which never leaves the phone but to OpenRouter.
 *
 * OpenRouter answers a code it refuses, or one past its ten minutes, with 403: both are
 * [KeyExchange.Refused]; the app tells them apart by the time taken.
 *
 * @param net the process's one `NetClient`.
 * @param hasher SHA-256, for the challenge.
 * @param random that many random bytes, from a cryptographically strong source, for the verifier.
 * @param baseUrl OpenRouter's origin, for tests.
 */
class OpenRouterSignIn(
    private val net: NetClient,
    private val hasher: Hasher,
    private val random: (Int) -> ByteArray,
    private val baseUrl: String = OpenRouterProvider.BASE_URL,
) : KeySignIn {
    override val callback: String = CALLBACK

    override fun begin(): SignInStart {
        val verifier = BASE64URL.encode(random(VERIFIER_BYTES))
        val state = BASE64URL.encode(random(STATE_BYTES))
        val query =
            listOf(
                "callback_url" to "$callback?$STATE=$state",
                "code_challenge" to challengeOf(verifier, hasher),
                "code_challenge_method" to METHOD,
                "key_label" to KEY_LABEL,
            ).joinToString("&") { (name, value) -> "$name=${encoded(value)}" }
        return SignInStart(verifier, state, "$baseUrl/auth?$query")
    }

    override suspend fun exchange(
        code: String,
        verifier: String,
    ): KeyExchange {
        val body =
            buildJsonObject {
                put("code", code)
                put("code_verifier", verifier)
                put("code_challenge_method", METHOD)
            }
        val request =
            NetRequest(
                url = "$baseUrl/api/v1/auth/keys",
                method = NetMethod.POST,
                body = NetBody(body.toString(), "application/json"),
            )
        return when (val result = net.send(request)) {
            is NetResult.Failure -> {
                if (result.error is NetError.Offline) KeyExchange.Offline else KeyExchange.Refused
            }

            is NetResult.Success -> {
                val key =
                    result.value
                        .takeIf { it.isSuccess }
                        ?.let { decode<WireKey>(it.body)?.key }
                        ?.takeIf { it.isNotBlank() }
                key?.let { KeyExchange.Key(Secret(it)) } ?: KeyExchange.Refused
            }
        }
    }

    companion object {
        /** Where OpenRouter sends the code: the app's own scheme, which its main activity takes. */
        const val CALLBACK: String = "app.sleevesight://openrouter"

        /** The callback's parameter that carries the sign-in's state back. */
        const val STATE: String = "s"

        /** 16 bytes of state: 22 characters, enough that no other sign-in's answer matches. */
        const val STATE_BYTES: Int = 16

        /** 32 bytes: a 43-character verifier, as RFC 7636 asks (43 to 128). */
        const val VERIFIER_BYTES: Int = 32

        /** The name the key gets on the user's OpenRouter account. */
        const val KEY_LABEL: String = "Sleevesight"

        private const val METHOD = "S256"

        private val BASE64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** [verifier]'s challenge: its SHA-256 by [hasher], base64url without padding (RFC 7636, S256). */
        fun challengeOf(
            verifier: String,
            hasher: Hasher,
        ): String = BASE64URL.encode(hasher.sha256(verifier.encodeToByteArray()))

        // RFC 3986's unreserved characters, which a query value carries as they are.
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** [value] percent-encoded for a query (UTF-8): all but the unreserved characters. */
        internal fun encoded(value: String): String =
            value.encodeToByteArray().joinToString("") { byte ->
                val code = byte.toInt() and BYTE
                val char = code.toChar()
                if (char in UNRESERVED) char.toString() else "%" + code.toString(HEX).uppercase().padStart(2, '0')
            }

        private const val BYTE = 0xFF
        private const val HEX = 16
    }
}
