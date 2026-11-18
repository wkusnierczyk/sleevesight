package app.sleevesight.ai.openrouter

import app.sleevesight.ai.ReplayNetClient
import app.sleevesight.ai.ReplayNetClient.Companion.failure
import app.sleevesight.ai.ReplayNetClient.Companion.ok
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmKeyCrypto
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/** Signing in with OpenRouter (#1157), against canned answers only: nothing is sent to OpenRouter. */
class OpenRouterSignInTest {
    // A sign-in whose verifier's bytes are [byte], repeated.
    private fun signIn(
        net: ReplayNetClient = ReplayNetClient { ok("") },
        byte: Int = 7,
    ) = OpenRouterSignIn(net, JvmHasher, { size -> ByteArray(size) { byte.toByte() } })

    // The page's query, each value decoded from its `%XX`s.
    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { pair ->
            val value = pair.substringAfter('=')
            val bytes = mutableListOf<Byte>()
            var at = 0
            while (at < value.length) {
                if (value[at] == '%') {
                    bytes += value.substring(at + 1, at + 3).toInt(16).toByte()
                    at += 3
                } else {
                    bytes += value[at].code.toByte()
                    at++
                }
            }
            pair.substringBefore('=') to bytes.toByteArray().decodeToString()
        }

    @Test
    fun `the challenge is the verifier's SHA-256 in base64url, as RFC 7636's example has it`() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OpenRouterSignIn.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", JvmHasher),
        )
    }

    @Test
    fun `a sign-in opens OpenRouter's page with the callback and the challenge, never the verifier`() {
        val start = signIn().begin()
        assertEquals(43, start.verifier.length)
        assert(start.url.startsWith("https://openrouter.ai/auth?"))
        val query = query(start.url)
        // The callback, an https App Link on the app's site (#1396), with the sign-in's state, which the
        // answer carries back.
        assertEquals("https://sleevesight.app/auth/openrouter/?s=${start.state}", query["callback_url"])
        assertEquals(22, start.state.length)
        assertEquals(OpenRouterSignIn.challengeOf(start.verifier, JvmHasher), query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("Sleevesight", query["key_label"])
        assertFalse(start.verifier in start.url)
        assertFalse(start.verifier in start.toString())
    }

    @Test
    fun `each sign-in has a verifier of its own`() {
        val signIn = OpenRouterSignIn(ReplayNetClient { ok("") }, JvmHasher, JvmKeyCrypto::random)
        assertNotEquals(signIn.begin().verifier, signIn.begin().verifier)
        assertNotEquals(signIn(byte = 1).begin().verifier, signIn(byte = 2).begin().verifier)
    }

    @Test
    fun `a query value keeps the unreserved characters and encodes the rest`() {
        assertEquals(
            "https%3A%2F%2Fsleevesight.app%2Fauth%2Fopenrouter%2F",
            OpenRouterSignIn.encoded("https://sleevesight.app/auth/openrouter/"),
        )
        assertEquals("a-b_c.d~e%20%C3%A9", OpenRouterSignIn.encoded("a-b_c.d~e é"))
    }

    @Test
    fun `the code and verifier are exchanged for the key`() =
        runTest {
            val net = ReplayNetClient { ok("""{"key":"sk-or-v1-abc","user_id":"user_1"}""") }
            val exchange = signIn(net).exchange("the-code", "the-verifier")
            assertEquals("sk-or-v1-abc", assertIs<KeyExchange.Key>(exchange).key.reveal())
            val sent = net.requests.single()
            assertEquals("https://openrouter.ai/api/v1/auth/keys", sent.url)
            assertEquals(NetMethod.POST, sent.method)
            val body = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals("the-code", body["code"]!!.jsonPrimitive.content)
            assertEquals("the-verifier", body["code_verifier"]!!.jsonPrimitive.content)
            assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
            assertFalse("the-verifier" in sent.toString())
        }

    @Test
    fun `a code refused, an answer with no key, or no connection give no key`() =
        runTest {
            val cases =
                listOf(
                    failure(NetError.Unauthorized(403)) to KeyExchange.Refused,
                    ok("""{"error":{"code":400,"message":"Invalid code_challenge_method"}}""", status = 400) to
                        KeyExchange.Refused,
                    ok("""{"user_id":"user_1"}""") to KeyExchange.Refused,
                    ok("not json") to KeyExchange.Refused,
                    failure(NetError.Offline("UnknownHostException")) to KeyExchange.Offline,
                )
            for ((answer, expected) in cases) {
                assertSame(expected, signIn(ReplayNetClient { answer }).exchange("c", "v"))
            }
        }
}
