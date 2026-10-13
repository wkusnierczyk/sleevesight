package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
            val client =
                mockClient(log = log) { request ->
                    sent += request.url.toString()
                    sent += request.headers.entries().flatMap { it.value }
                    respond("{}", HttpStatusCode.OK)
                }
            val request =
                NetRequest(
                    url = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo",
                    query = mapOf("artist" to "Reem", "track" to "Pathways"),
                    headers = mapOf("Accept" to "application/json"),
                    secrets = NetSecrets(query = mapOf("api_key" to key), headers = mapOf("x-api-key" to key)),
                )

            client.send(request)

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
        val odd = "a+b/c=="
        val request =
            NetRequest(
                url = "https://example.org/v1/$odd/items?q=$odd",
                secrets = NetSecrets(headers = mapOf("x-custom" to odd)),
            )

        val line = Redaction.describe(request)

        assertFalse(odd in line, line)
        assertFalse("a%2Bb%2Fc%3D%3D" in line, line)
    }

    @Test
    fun `a failed request logs no key, whatever the exception message says`() =
        runTest {
            val log = RecordingLog()
            val client =
                mockClient(log = log) { request -> throw IOException("failed to reach ${request.url}") }

            val result =
                client.send(
                    NetRequest("https://example.org/a", secrets = NetSecrets(query = mapOf("key" to key))),
                )

            assertTrue(result is NetResult.Failure && result.error is NetError.Offline)
            assertTrue(log.lines.isNotEmpty())
            log.lines.forEach { assertFalse(key in it, it) }
        }
}
