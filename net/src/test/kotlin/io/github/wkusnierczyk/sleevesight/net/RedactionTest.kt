package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
                    "GET https://ws.audioscrobbler.com/…?method&artist&track&api_key {Accept, x-api-key} -> 200",
                ),
                line,
            )
            assertFalse(key in request.toString())
        }

    @Test
    fun `a request without credentials shows its path, and never a value`() {
        val request =
            NetRequest(
                url = "https://example.org/v1/search?q=reem#frag",
                query = mapOf("limit" to "5"),
                headers = mapOf("Accept" to "text/plain"),
            )

        assertEquals("GET https://example.org/v1/search?q&limit {Accept}", request.toString())
    }

    @Test
    fun `a request with credentials hides its path, whatever the secret looks like`() {
        val cases =
            listOf(
                "abc/def" to "/v1/abc/def",
                "ab/cdSECRET" to "/echo/ab/cdSECRET",
                "ab/cd" to "/v1/ab%252fcd",
                "sk-probe-SECRET0" to "/%73k-probe-SECRET0/%zz",
                "a+b/c==xyz" to "/x;token=a+b/c==xyz",
                "1" to "/v1",
            )
        for ((secret, path) in cases) {
            val request = NetRequest("https://example.org$path", secrets = NetSecrets(headers = mapOf("x-k" to secret)))

            assertEquals("GET https://example.org/… {x-k}", request.toString(), "$secret in $path")
        }
    }

    @Test
    fun `headers and parameters named like keys, and user info, count as credentials`() {
        val requests =
            listOf(
                NetRequest("https://example.org/v1/abc1234", headers = mapOf("Authorization" to "Bearer abc1234")),
                NetRequest("https://example.org/v1/abc1234", query = mapOf("api_key" to "abc1234")),
                NetRequest("https://example.org/v1/abc1234?token=abc1234"),
                NetRequest("https://u:abc1234@example.org/v1/abc1234"),
            )
        for (request in requests) {
            assertFalse("abc1234" in request.toString(), request.toString())
            assertTrue("/…" in request.toString(), request.toString())
        }
    }

    @Test
    fun `sensitive names are matched by token, not by substring`() {
        val sensitive =
            listOf(
                "Authorization",
                "Proxy-Authorization",
                "x-api-key",
                "x-goog-api-key",
                "api_key",
                "apiKey",
                "X-ApiKey",
                "access_token",
                "accessToken",
                "authToken",
                "X-Auth-Token",
                "client_secret",
                "password",
                "api_sig",
                "sk",
                "Cookie",
                "Set-Cookie",
                "sessionId",
                "code",
                "code_verifier",
                "key",
            )
        val ordinary =
            listOf("author", "keyword", "monkey", "Accept", "q", "artist", "limit", "format", "method", "track")

        sensitive.forEach { assertTrue(Redaction.isSensitive(it), it) }
        ordinary.forEach { assertFalse(Redaction.isSensitive(it), it) }
    }

    @Test
    fun `secrets and validation results print no names or URLs`() {
        val secrets = NetSecrets(query = mapOf(key to "v"), headers = mapOf("x-$key" to "v"))

        assertEquals("NetSecrets(1 query, 1 headers)", secrets.toString())
        assertEquals("Valid", Validated.Valid(Url("https://example.org/a?api_key=$key")).toString())
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
    fun `an invalid header is reported by a fixed label, not its name or value`() =
        runTest {
            val log = RecordingLog()
            var calls = 0
            withClient(log = log, handler = {
                calls++
                respond("")
            }) { client ->
                for (headers in listOf(
                    mapOf("x-api-key" to "$key\n"),
                    mapOf(key to "bad\n"),
                    mapOf("bad name" to key),
                )) {
                    val result =
                        client.send(
                            NetRequest("https://example.org/a", secrets = NetSecrets(headers = headers)),
                        )

                    assertEquals(NetResult.Failure(NetError.InvalidRequest("header")), result)
                    assertFalse(key in result.toString())
                }
            }

            assertEquals(0, calls)
            // Header names are logged by design; a value never is.
            assertFalse(key in log.lines[0], log.lines[0])
            assertFalse(key in log.lines[2], log.lines[2])
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
    fun `a request with credentials follows no redirect to another origin, however the target is spelled`() =
        runTest {
            for (location in listOf(
                "https://other.example.net/x",
                "https://other.example.net/%73k-probe-SECRET0/%zz",
            )) {
                var calls = 0
                val result =
                    withClient(handler = {
                        calls++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    }) {
                        it.send(
                            NetRequest(
                                "https://api.example.org/a",
                                secrets =
                                    NetSecrets(
                                        headers =
                                            mapOf(
                                                "x-api-key" to "sk-probe-SECRET0",
                                            ),
                                    ),
                            ),
                        )
                    }

                assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status, location)
                assertEquals(1, calls, location)
            }
        }

    @Test
    fun `a request without credentials follows a redirect to another origin`() =
        runTest {
            val hosts = mutableListOf<String>()
            withClient(handler = { request ->
                hosts += request.url.host
                if (hosts.size ==
                    1
                ) {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://other.example.net/x"),
                    )
                } else {
                    respond("ok")
                }
            }) { it.send(NetRequest("https://api.example.org/a", headers = mapOf("Accept" to "x/y"))) }

            assertEquals(listOf("api.example.org", "other.example.net"), hosts)
        }

    @Test
    fun `a key echoed into a same-origin redirect's path is not logged`() =
        runTest {
            val log = RecordingLog()
            val echoed = "key-SECRET123"
            withClient(log = log, handler = { request ->
                if (request.url.encodedPath == "/a") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/echo/$echoed"))
                } else {
                    respond("ok")
                }
            }) {
                it.send(
                    NetRequest("https://api.example.org/a", secrets = NetSecrets(query = mapOf("api_key" to echoed))),
                )
            }

            assertEquals(2, log.lines.size)
            log.lines.forEach { assertFalse(echoed in it, it) }
            assertTrue(log.lines[1].startsWith("GET https://api.example.org/… -> 200"), log.lines[1])
        }

    @Test
    fun `user info is never sent, nor carried into a redirect`() =
        runTest {
            val users = mutableListOf<String?>()
            withClient(handler = { request ->
                users += request.url.user
                if (request.url.encodedPath == "/p") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/x"))
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
                var calls = 0
                val result =
                    withClient(handler = {
                        calls++
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
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

                assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status, location)
                assertEquals(1, calls, location)
            }
        }

    @Test
    fun `a request without credentials drops nothing on a redirect`() =
        runTest {
            val accepts = mutableListOf<String?>()
            withClient(handler = { request ->
                accepts += request.headers["Accept"]
                if (accepts.size ==
                    1
                ) {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://b.example.org/"))
                } else {
                    respond("ok")
                }
            }) { it.send(NetRequest("https://a.example.org/", headers = mapOf("Accept" to "x/y"))) }

            assertEquals(listOf<String?>("x/y", "x/y"), accepts)
        }
}
