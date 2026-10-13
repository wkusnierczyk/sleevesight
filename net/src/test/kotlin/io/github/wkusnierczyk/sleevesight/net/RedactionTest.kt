package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedactionTest {
    private val key = "sk-ant-0123456789abcdefSECRET"

    /** The architecture's test (Structural constraints): a request carrying a key, logged, and the key absent. */
    @Test
    fun `a logged request carrying a key does not show the key`() =
        runTest {
            val log = RecordingLog()
            val sent = mutableListOf<String>()
            val request =
                NetRequest(
                    url = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo",
                    query = mapOf("artist" to "Reem", "track" to "Pathways"),
                    headers = mapOf("Accept" to "application/json"),
                    secrets = NetSecrets(query = mapOf("api_key" to key), headers = mapOf("x-api-key" to key)),
                )

            withClient(log = log, handler = { received ->
                sent += received.url.toString()
                sent += received.headers.entries().flatMap { it.value }
                respond("{}", HttpStatusCode.OK)
            }) { it.send(request) }

            assertTrue(sent.any { key in it }, "the key was sent")
            assertEquals(1, log.lines.size)
            assertFalse(key in log.lines.single(), log.lines.single())
            assertTrue("artist=Reem" in log.lines.single(), "the rest of the request is logged")
            assertFalse(key in request.toString())
        }

    @Test
    fun `values whose names look like keys are redacted even when not declared secret`() {
        val request =
            NetRequest(
                url = "https://user:hunter2@example.org/search?q=reem&token=$key#frag",
                query = mapOf("api_key" to key, "Signature" to key, "sk" to key, "limit" to "5"),
                headers = mapOf("Authorization" to "Bearer $key", "x-goog-api-key" to key, "Accept" to "*/*"),
            )

        val line = Redaction.describe(request)

        assertFalse(key in line, line)
        assertFalse("hunter2" in line, line)
        assertTrue("q=reem" in line && "limit=5" in line && "Accept: */*" in line, line)
    }

    @Test
    fun `a declared secret is scrubbed wherever it appears, plain or URL-encoded`() {
        val odd = "a+b/c==xyz"
        val request =
            NetRequest(
                url = "https://example.org/v1/$odd/items?q=$odd",
                secrets = NetSecrets(headers = mapOf("x-custom" to odd)),
            )

        val line = Redaction.describe(request)

        assertFalse(odd in line, line)
        assertFalse("a%2Bb%2Fc%3D%3Dxyz" in line, line)
    }

    @Test
    fun `a partly encoded secret is scrubbed`() {
        val token = "sk-probe-SECRET0"
        val request =
            NetRequest(
                url = "https://example.org/v1/%73k-probe-SECRET0/x?q=%2573k-probe-SECRET0",
                secrets = NetSecrets(headers = mapOf("x-api-key" to token)),
            )

        val line = Redaction.describe(request)

        assertFalse("probe-SECRET0" in line, line)
    }

    @Test
    fun `the token of a Bearer header is scrubbed wherever else it appears`() {
        val token = "tok-0123456789abcdef"
        val request =
            NetRequest(
                url = "https://example.org/v1/items",
                query = mapOf("q" to token),
                secrets = NetSecrets(headers = mapOf("Authorization" to "Bearer $token")),
            )

        val line = Redaction.describe(request)

        assertFalse(token in line, line)
    }

    @Test
    fun `a failed request logs no key, whatever the exception message says`() =
        runTest {
            val log = RecordingLog()
            val result =
                withClient(log = log, handler = { request -> throw IOException("failed to reach ${request.url}") }) {
                    it.send(NetRequest("https://example.org/a", secrets = NetSecrets(query = mapOf("key" to key))))
                }

            assertTrue(result is NetResult.Failure && result.error is NetError.Offline)
            assertTrue(log.lines.isNotEmpty())
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a key with a trailing newline is an invalid header, named but not shown`() =
        runTest {
            val log = RecordingLog()
            var calls = 0
            val result =
                withClient(log = log, handler = {
                    calls++
                    respond("")
                }) {
                    it.send(
                        NetRequest(
                            "https://example.org/a",
                            secrets =
                                NetSecrets(
                                    headers =
                                        mapOf(
                                            "x-api-key" to "$key\n",
                                        ),
                                ),
                        ),
                    )
                }

            assertEquals(NetResult.Failure(NetError.InvalidRequest("header x-api-key")), result)
            assertEquals(0, calls)
            assertFalse(key in result.toString())
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a malformed URL carrying a key is an invalid request, and its description hides the URL`() =
        runTest {
            val log = RecordingLog()
            val request = NetRequest("https://example.org:bad/?api_key=$key")

            val result = withClient(log = log, handler = { respond("") }) { it.send(request) }

            assertEquals(NetResult.Failure(NetError.InvalidRequest("url")), result)
            assertFalse(key in request.toString(), request.toString())
            assertTrue(log.lines.isNotEmpty())
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a parser failure keeps only the exception's type, not its message`() =
        runTest {
            val result =
                withClient(handler = { respond("{}") }) { client ->
                    client.fetch(NetRequest("https://example.org/a")) { error("cannot read ?api_key=$key") }
                }

            assertEquals(NetResult.Failure(NetError.Parse("IllegalStateException")), result)
            assertFalse(key in result.toString())
        }

    @Test
    fun `a redirect to another origin drops the secrets and the headers named like keys`() =
        runTest {
            val log = RecordingLog()
            val seen = mutableListOf<Pair<String, Map<String, String?>>>()
            withClient(log = log, handler = { request ->
                val names = listOf("x-api-key", "Authorization", "Accept")
                seen += request.url.host to names.associateWith { request.headers[it] }
                if (request.url.host == "api.example.org") {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://other.example.net/x?echo=$key"),
                    )
                } else {
                    respond("ok")
                }
            }) { client ->
                client.send(
                    NetRequest(
                        "https://api.example.org/a",
                        headers = mapOf("Authorization" to "Bearer plain-header-token", "Accept" to "x/y"),
                        secrets = NetSecrets(headers = mapOf("x-api-key" to key)),
                    ),
                )
            }

            assertEquals(listOf("api.example.org", "other.example.net"), seen.map { it.first })
            val (_, redirected) = seen[1]
            assertNull(redirected["x-api-key"])
            assertNull(redirected["Authorization"])
            assertEquals("x/y", redirected["Accept"])
            assertEquals(2, log.lines.size)
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a redirect within the origin keeps the secret headers`() =
        runTest {
            val keys = mutableListOf<String?>()
            withClient(handler = { request ->
                keys += request.headers["x-api-key"]
                if (request.url.encodedPath == "/a") {
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/b"))
                } else {
                    respond("ok")
                }
            }) {
                it.send(
                    NetRequest("https://api.example.org/a", secrets = NetSecrets(headers = mapOf("x-api-key" to key))),
                )
            }

            assertEquals(listOf<String?>(key, key), keys)
        }
}
