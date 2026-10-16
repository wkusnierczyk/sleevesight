package app.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Redirects and what the log shows of their hops: which chains stay in their origin, and which hops are hidden. */
class RedirectLogTest {
    private val key = "sk-ant-0123456789abcdefSECRET"

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

            assertEquals(
                listOf(
                    "GET https://api.example.org/a {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://api.example.org/… {0 headers} -> 200 in 0 ms (attempt 1)",
                ),
                log.lines,
            )
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

            assertEquals(
                listOf(
                    "GET https://api.example.org/a {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://api.example.org/… {0 headers} -> 200 in 0 ms (attempt 1)",
                ),
                log.lines,
            )
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
                assertEquals(
                    listOf(
                        "GET https://a.example.org/start {1 header} -> 302 in 0 ms (attempt 1)",
                        "GET https://a.example.org/… {1 header} -> 302 in 0 ms (attempt 1)",
                    ),
                    log.lines,
                    echo,
                )
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
            assertEquals(
                listOf(
                    "GET https://a.example.org/start {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://a.example.org/… {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://b.example.org/… {0 headers} -> 200 in 0 ms (attempt 1)",
                ),
                log.lines,
            )
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
            assertEquals(
                listOf(
                    "GET https://api.example.org/a {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://api.example.org/… {0 headers} -> 302 in 0 ms (attempt 1)",
                    "GET https://cdn.example.net/… {0 headers} -> 200 in 0 ms (attempt 1)",
                ),
                log.lines,
            )
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
                assertEquals(
                    listOf(
                        "GET https://a.example.org/p {0 headers} -> 302 in 0 ms (attempt 1)",
                        "GET https://$host/… {0 headers} -> 302 in 0 ms (attempt 1)",
                        "GET https://$host/… {0 headers} -> 200 in 0 ms (attempt 1)",
                    ),
                    log.lines,
                    location,
                )
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
                val host = url.removePrefix("https://").substringBefore('/')
                assertEquals(listOf("GET https://$host/… {0 headers} -> 302 in 0 ms (attempt 1)"), log.lines, url)
            }
        }

    @Test
    fun `a server-chosen token in a public chain's Location path or bare query name is not logged`() =
        runTest {
            for (location in listOf("/echo/$key", "/done?$key")) {
                val log = RecordingLog()
                withClient(log = log, handler = { request ->
                    if (request.url.encodedPath == "/start") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                    } else {
                        respond("ok")
                    }
                }) { it.send(NetRequest("https://a.example.org/start")) }

                assertEquals(
                    listOf(
                        "GET https://a.example.org/start {0 headers} -> 302 in 0 ms (attempt 1)",
                        "GET https://a.example.org/… {0 headers} -> 200 in 0 ms (attempt 1)",
                    ),
                    log.lines,
                    location,
                )
            }
        }
}
