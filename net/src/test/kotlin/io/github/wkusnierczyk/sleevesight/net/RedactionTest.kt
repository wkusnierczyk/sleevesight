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
import kotlin.test.assertIs
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
            val line = log.lines.single()
            assertFalse(key in line, line)
            assertTrue(
                line.startsWith(
                    "GET https://ws.audioscrobbler.com/2.0/?method&artist&track&api_key {Accept, x-api-key}",
                ),
                line,
            )
            assertFalse(key in request.toString())
        }

    @Test
    fun `no header or query value is logged, whatever its name, and no user info`() {
        val request =
            NetRequest(
                url = "https://user:hunter2@example.org/search?q=reem&token=$key#frag",
                query = mapOf("api_key" to key, "limit" to "5"),
                headers = mapOf("Authorization" to "Bearer $key", "Accept" to "text/plain"),
            )

        val line = request.toString()

        assertEquals("GET https://example.org/search?q&token&api_key&limit {Authorization, Accept}", line)
    }

    @Test
    fun `a secret in the path is hidden, as written, pre-encoded or double-encoded`() {
        for ((secret, path) in listOf(
            "ab%41cdefghij" to "ab%41cdefghij",
            "ab%41cdefghij" to "ab%2541cdefghij",
            "a%252FbSECRET" to "a%252FbSECRET",
            "a+b/c==xyz" to "a%2Bb%2Fc%3D%3Dxyz",
            "sk-probe-SECRET0" to "%73k-probe-SECRET0",
        )) {
            val request =
                NetRequest(
                    "https://example.org/v1/$path/x",
                    secrets =
                        NetSecrets(
                            headers =
                                mapOf(
                                    "x-k" to secret,
                                ),
                        ),
                )

            assertEquals("GET https://example.org/v1/REDACTED/x {x-k}", request.toString(), "$secret in $path")
        }
    }

    @Test
    fun `the token of a Bearer header is hidden wherever it appears, however short`() {
        val request =
            NetRequest(
                url = "https://example.org/v1/abc1234",
                secrets = NetSecrets(headers = mapOf("Authorization" to "Bearer abc1234")),
            )

        assertEquals("GET https://example.org/v1/REDACTED {Authorization}", request.toString())
    }

    @Test
    fun `a quoted credential is hidden without its quotes`() {
        val request =
            NetRequest(
                url = "https://example.org/v1/tok123",
                headers = mapOf("Authorization" to "OAuth \"tok123\""),
            )

        assertFalse("tok123" in request.toString(), request.toString())
    }

    @Test
    fun `an encoded sensitive query name shows no value`() {
        val request =
            NetRequest(
                "https://example.org/a?%256B%2565%2579=SECRET&q=1",
                secrets =
                    NetSecrets(
                        query =
                            mapOf(
                                "x" to "1",
                            ),
                    ),
            )

        val line = request.toString()

        assertFalse("SECRET" in line, line)
        assertFalse("=" in line, line)
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
            val seen = mutableListOf<Pair<String, Map<String, String?>>>()
            withClient(handler = { request ->
                val names = listOf("x-api-key", "Authorization", "Accept")
                seen += request.url.host to names.associateWith { request.headers[it] }
                if (request.url.host == "api.example.org") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example.net/x"))
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
        }

    @Test
    fun `a redirect to another origin that would carry a key is not followed`() =
        runTest {
            val log = RecordingLog()
            var calls = 0
            val result =
                withClient(log = log, handler = {
                    calls++
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://other.example.net/x?echo=$key"),
                    )
                }) {
                    it.send(
                        NetRequest("https://api.example.org/a", secrets = NetSecrets(query = mapOf("api_key" to key))),
                    )
                }

            assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status)
            assertEquals(1, calls)
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a key from a header named like one, echoed into a redirected path, is not logged`() =
        runTest {
            val log = RecordingLog()
            val echoed = "key-SECRET123"
            withClient(log = log, handler = { request ->
                if (request.url.encodedPath == "/a") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/echo/$echoed"))
                } else {
                    respond("ok")
                }
            }) { it.send(NetRequest("https://api.example.org/a", headers = mapOf("x-api-key" to echoed))) }

            assertEquals(2, log.lines.size)
            assertTrue("/echo/REDACTED" in log.lines[1], log.lines[1])
            log.lines.forEach { assertFalse(echoed in it, it) }
        }

    @Test
    fun `user info is never sent, nor carried into a redirect`() =
        runTest {
            val users = mutableListOf<String?>()
            withClient(handler = { request ->
                users += request.url.user
                if (request.url.host == "a.example.org") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "//b.example.org/x"))
                } else {
                    respond("ok")
                }
            }) { it.send(NetRequest("https://u:pw@a.example.org/p")) }

            assertEquals(listOf<String?>(null, null), users)
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

    @Test
    fun `a change of scheme or port is another origin`() =
        runTest {
            for (location in listOf("http://api.example.org/b", "https://api.example.org:8443/b")) {
                val keys = mutableListOf<String?>()
                withClient(handler = { request ->
                    keys += request.headers["x-api-key"]
                    if (request.url.encodedPath == "/a") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    } else {
                        respond("ok")
                    }
                }) {
                    it.send(
                        NetRequest(
                            "https://api.example.org/a",
                            secrets =
                                NetSecrets(
                                    headers =
                                        mapOf(
                                            "x-api-key" to key,
                                        ),
                                ),
                        ),
                    )
                }

                assertEquals(listOf<String?>(key, null), keys, location)
            }
        }
}
