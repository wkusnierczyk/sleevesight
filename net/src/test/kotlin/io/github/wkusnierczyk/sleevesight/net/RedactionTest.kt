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
                    "GET https://ws.audioscrobbler.com/2.0/?method&artist&track {2 headers} -> 200",
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

        assertEquals("GET https://example.org/v1/search?q&limit {1 header}", request.toString())
    }

    @Test
    fun `a sensitive URL hides its path, whatever the key in it looks like`() {
        val cases =
            listOf(
                "abc/def" to "/v1/abc/def",
                "ab/cdSECRET" to "/echo/ab/cdSECRET",
                "ab/cd" to "/v1/ab%252fcd",
                "sk-probe-SECRET0" to "/%73k-probe-SECRET0/%zz",
                "a+b/c==xyz" to "/x;token=a+b/c==xyz",
            )
        for ((secret, path) in cases) {
            val request = NetRequest("https://example.org$path?q=1", sensitiveUrl = true)

            assertEquals("GET https://example.org/… {0 headers}", request.toString(), "$secret in $path")
        }
    }

    @Test
    fun `a first hop with credentials only in secrets or headers shows its public path, without secret names`() {
        val request =
            NetRequest(
                "https://api.anthropic.com/v1/messages",
                NetMethod.POST,
                query = mapOf("beta" to "true"),
                headers = mapOf("Authorization" to "Bearer $key"),
                secrets = NetSecrets(query = mapOf("api_key" to key), headers = mapOf("x-api-key" to key)),
                body = NetBody("{}", "application/json"),
            )

        assertEquals("POST https://api.anthropic.com/v1/messages?beta {2 headers}", request.toString())
    }

    @Test
    fun `a URL that may hold a credential hides its path and query names`() {
        val requests =
            listOf(
                NetRequest("https://example.org/v1/abc1234", query = mapOf("api_key" to "abc1234")),
                NetRequest("https://example.org/v1/abc1234?token=abc1234"),
                NetRequest("https://u:abc1234@example.org/v1/abc1234"),
                NetRequest("https://example.org/v1/abc1234?q=1", sensitiveUrl = true),
            )
        for (request in requests) {
            assertEquals("GET https://example.org/… {0 headers}", request.toString())
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
            assertEquals(3, log.lines.size)
            log.lines.forEach { line ->
                assertTrue(
                    line.startsWith("GET https://example.org/a {0 headers} -> Offline(reason=IOException)"),
                    line,
                )
                assertFalse(key in line, line)
            }
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
                    mapOf("Authorization: Bearer $key" to ""),
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
            assertEquals(4, log.lines.size)
            log.lines.forEach { line ->
                assertEquals("GET https://example.org/a {1 header} -> InvalidRequest(field=header)", line)
            }
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
            assertTrue(log.lines[1].startsWith("GET https://api.example.org/… {0 headers} -> 200"), log.lines[1])
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

    @Test
    fun `sensitive names match whatever their case and separators`() {
        val sensitive =
            listOf(
                "cOoKiE",
                "AuthoriZation",
                "X-Api-KeY",
                "auth[token]",
                "api key",
                "api:key",
                "key2",
                "apiKey2",
                "X-Api-Key2",
            )
        sensitive.forEach { assertTrue(Redaction.isSensitive(it), it) }
        listOf(
            "author",
            "keyword",
            "monkey",
            "Author",
            "KEYWORD",
        ).forEach { assertFalse(Redaction.isSensitive(it), it) }
    }

    @Test
    fun `a swapped header name and value is counted, not shown`() =
        runTest {
            val log = RecordingLog()
            withClient(log = log, handler = { respond("ok") }) {
                it.send(NetRequest("https://example.org/a", secrets = NetSecrets(headers = mapOf(key to "x-api-key"))))
                it.send(NetRequest("https://example.org/a", headers = mapOf(key to "x-api-key")))
            }

            assertEquals(2, log.lines.size)
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a key as a bare query name from the server's Location is not shown`() =
        runTest {
            val log = RecordingLog()
            withClient(log = log, handler = { request ->
                if (request.url.encodedPath == "/a") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/done?$key"))
                } else {
                    respond("ok")
                }
            }) {
                it.send(
                    NetRequest("https://api.example.org/a", secrets = NetSecrets(query = mapOf("api_key" to key))),
                )
            }

            assertEquals(2, log.lines.size)
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a chain started with secrets stays in its origin when the server hands back a key`() =
        runTest {
            for (echo in listOf("api_key=$key", "SESSIONID=$key")) {
                val log = RecordingLog()
                val hosts = mutableListOf<String>()
                val result =
                    withClient(log = log, handler = { request ->
                        hosts += request.url.host
                        when (request.url.encodedPath) {
                            "/start" ->
                                respond(
                                    "",
                                    HttpStatusCode.Found,
                                    headersOf(HttpHeaders.Location, "/echo/$key?$echo"),
                                )
                            else ->
                                respond(
                                    "",
                                    HttpStatusCode.Found,
                                    headersOf(HttpHeaders.Location, "https://b.example.org/$key"),
                                )
                        }
                    }) {
                        it.send(
                            NetRequest(
                                "https://a.example.org/start",
                                secrets =
                                    NetSecrets(
                                        headers =
                                            mapOf(
                                                "x-k" to "v",
                                            ),
                                    ),
                            ),
                        )
                    }

                assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status, echo)
                assertEquals(listOf("a.example.org", "a.example.org"), hosts, echo)
                assertEquals(2, log.lines.size, echo)
                assertTrue(log.lines[0].startsWith("GET https://a.example.org/start {1 header} -> 302"), log.lines[0])
                assertTrue(log.lines[1].startsWith("GET https://a.example.org/… {1 header} -> 302"), log.lines[1])
                log.lines.forEach { assertFalse(key in it, it) }
            }
        }

    @Test
    fun `a public chain handed a key by the server follows on, hidden in the log`() =
        runTest {
            val log = RecordingLog()
            val hosts = mutableListOf<String>()
            withClient(log = log, handler = { request ->
                hosts += request.url.host
                when (request.url.encodedPath) {
                    "/start" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "/echo/$key?SESSIONID=$key"),
                        )
                    "/echo/$key" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://b.example.org/$key"),
                        )
                    else -> respond("ok")
                }
            }) { it.send(NetRequest("https://a.example.org/start")) }

            assertEquals(listOf("a.example.org", "a.example.org", "b.example.org"), hosts)
            assertEquals(3, log.lines.size)
            assertTrue(log.lines[0].startsWith("GET https://a.example.org/start {0 headers} -> 302"), log.lines[0])
            assertTrue(log.lines[1].startsWith("GET https://a.example.org/… {0 headers} -> 302"), log.lines[1])
            assertTrue(log.lines[2].startsWith("GET https://b.example.org/… {0 headers} -> 200"), log.lines[2])
            log.lines.forEach { assertFalse(key in it, it) }
        }

    @Test
    fun `a server-issued token in a Location hides the rest of the chain, which may leave its origin`() =
        runTest {
            val log = RecordingLog()
            val hosts = mutableListOf<String>()
            withClient(log = log, handler = { request ->
                hosts += request.url.host
                when {
                    request.url.encodedPath == "/a" ->
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "/next?token=issued"),
                        )
                    request.url.host == "api.example.org" ->
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/x"))
                    else -> respond("ok")
                }
            }) { it.send(NetRequest("https://api.example.org/a")) }

            assertEquals(listOf("api.example.org", "api.example.org", "cdn.example.net"), hosts)
            assertEquals(3, log.lines.size)
            assertTrue(log.lines[0].startsWith("GET https://api.example.org/a {0 headers} -> 302"), log.lines[0])
            assertTrue(log.lines[1].startsWith("GET https://api.example.org/… {0 headers} -> 302"), log.lines[1])
            assertTrue(log.lines[2].startsWith("GET https://cdn.example.net/… {0 headers} -> 200"), log.lines[2])
        }

    @Test
    fun `user info in a Location is judged before it is stripped, in and out of the origin`() =
        runTest {
            for (location in listOf("https://u:SECRET@a.example.org/next", "https://u:SECRET@b.example.org/next")) {
                val log = RecordingLog()
                val users = mutableListOf<String?>()
                withClient(log = log, handler = { request ->
                    users += request.url.user
                    when (request.url.encodedPath) {
                        "/p" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                        "/next" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/x"))
                        else -> respond("ok")
                    }
                }) { it.send(NetRequest("https://a.example.org/p")) }

                val host = location.substringAfter('@').substringBefore('/')
                assertEquals(listOf<String?>(null, null, null), users, location)
                assertEquals(3, log.lines.size, location)
                assertTrue(log.lines[0].startsWith("GET https://a.example.org/p {0 headers} -> 302"), log.lines[0])
                assertTrue(log.lines[1].startsWith("GET https://$host/… {0 headers} -> 302"), log.lines[1])
                assertTrue(log.lines[2].startsWith("GET https://$host/… {0 headers} -> 200"), log.lines[2])
                log.lines.forEach { assertFalse("SECRET" in it, it) }
            }
        }

    @Test
    fun `a sensitive URL hides its path and query names and stays in its origin`() =
        runTest {
            for (url in listOf(
                "https://www.theaudiodb.com/api/v1/json/$key/search.php?s=reem",
                "https://dl.example.org/file?ticket=$key",
            )) {
                val log = RecordingLog()
                var calls = 0
                val result =
                    withClient(log = log, handler = {
                        calls++
                        respond(
                            "",
                            HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://other.example.net/x"),
                        )
                    }) { it.send(NetRequest(url, sensitiveUrl = true)) }

                assertEquals(302, assertIs<NetResult.Success<NetResponse>>(result).value.status, url)
                assertEquals(1, calls, url)
                assertEquals(1, log.lines.size, url)
                val host = url.removePrefix("https://").substringBefore('/')
                assertTrue(log.lines[0].startsWith("GET https://$host/… {0 headers} -> 302"), log.lines[0])
            }
        }

    @Test
    fun `compound credential names match without boundaries, and OAuth's code only whole`() {
        listOf(
            "CLIENTSECRET",
            "CLIENTSecret",
            "SESSIONID",
            "XAPIKEY",
            "clientSecret",
            "client_secret",
            "pwd",
            "JWT",
            "passphrase",
            "code",
            "Code",
        ).forEach { assertTrue(Redaction.isSensitive(it), it) }
        listOf(
            "languageCode",
            "countryCode",
            "barcode",
            "statusCode",
        ).forEach { assertFalse(Redaction.isSensitive(it), it) }
    }

    @Test
    fun `the names keyless sources use are not sensitive`() {
        val musicBrainz =
            listOf(
                "query",
                "fmt",
                "inc",
                "limit",
                "offset",
                "dismax",
                "type",
                "status",
                "artist",
                "recording",
                "release",
                "work",
            )
        val wikipedia =
            listOf(
                "action",
                "format",
                "formatversion",
                "prop",
                "titles",
                "pageids",
                "redirects",
                "exintro",
                "explaintext",
                "exsentences",
                "list",
                "srsearch",
                "srlimit",
                "sroffset",
                "origin",
                "uselang",
                "ppprop",
                "continue",
                "utf8",
            )
        val wikidata = listOf("ids", "props", "languages", "languagefallback", "sitefilter", "sites", "normalize")
        val headers = listOf("Accept", "Accept-Language", "User-Agent", "Api-User-Agent", "Accept-Encoding")
        (musicBrainz + wikipedia + wikidata + headers).forEach { assertFalse(Redaction.isSensitive(it), it) }
    }
}
