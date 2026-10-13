package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            assertEquals(
                listOf(
                    "GET https://ws.audioscrobbler.com/2.0/?method&artist&track {2 headers} -> 200 in 0 ms (attempt 1)",
                ),
                log.lines,
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
            val line = "GET https://example.org/a {0 headers} -> Offline(reason=IOException) in 0 ms"
            assertEquals(
                listOf(
                    "$line (attempt 1), retrying in 500ms",
                    "$line (attempt 2), retrying in 1s",
                    "$line (attempt 3)",
                ),
                log.lines,
            )
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
            assertEquals(listOf("GET <invalid url> {0 headers} -> InvalidRequest(field=url)"), log.lines)
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

            assertEquals(List(2) { "GET https://example.org/a {1 header} -> 200 in 0 ms (attempt 1)" }, log.lines)
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
            "X-Pwd",
            "user_pwd",
            "wallet_passphrase",
            "X-JWT-Assertion",
            "device_code",
            "deviceCode",
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
