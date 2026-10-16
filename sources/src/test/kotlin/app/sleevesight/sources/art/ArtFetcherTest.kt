package app.sleevesight.sources.art

import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtFetcherTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private class StubClient(
        private val answer: (NetRequest) -> NetResult<NetResponse>,
    ) : NetClient {
        val requests = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            requests += request
            return answer(request)
        }

        override fun close() = Unit
    }

    private fun response(
        status: Int = 200,
        type: String? = "image/jpeg",
        bytes: ByteArray = png,
    ): NetResult<NetResponse> =
        NetResult.Success(NetResponse(status, type?.let { mapOf("Content-Type" to listOf(it)) }.orEmpty(), bytes))

    @Test
    fun `an image is returned as served, with the request bounded`() =
        runTest {
            val client = StubClient { response() }
            val result = ArtFetcher(client).fetch("https://img.example/cover.jpg")
            assertContentEquals(png, assertIs<ArtResult.Image>(result).bytes)
            val request = client.requests.single()
            assertEquals("https://img.example/cover.jpg", request.url)
            assertEquals(ArtFetcher.MAX_BYTES, request.maxBytes)
            assertEquals(ArtFetcher.TIMEOUT, request.timeout)
            assertTrue(request.sensitiveUrl)
        }

    @Test
    fun `the log shows the host of a player's art URI, not its path or query`() =
        runTest {
            val client = StubClient { response() }
            ArtFetcher(client).fetch("https://img.example/u/listener-42/now-playing.jpg?token=abc&track=sundown")
            val logged = client.requests.single().toString()
            assertTrue("img.example" in logged, logged)
            listOf("listener-42", "now-playing", "token", "abc", "track", "sundown").forEach {
                assertFalse(it in logged, "$it in $logged")
            }
        }

    @Test
    fun `a response with no content type is kept`() =
        runTest {
            assertIs<ArtResult.Image>(ArtFetcher(StubClient { response(type = null) }).fetch("http://img.example/a"))
        }

    @Test
    fun `other schemes are not requested`() =
        runTest {
            val client = StubClient { response() }
            listOf("content://media/art/1", "file:///sdcard/a.jpg", "httpx://a", "http:a", "").forEach {
                assertEquals(ArtResult.Failed("not http"), ArtFetcher(client).fetch(it), it)
            }
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun `a status other than 2xx, a non-image or an empty body is no art`() =
        runTest {
            suspend fun fetch(answer: NetResult<NetResponse>) = ArtFetcher(StubClient { answer }).fetch(URL)
            assertEquals(ArtResult.Failed("status 404"), fetch(response(status = 404)))
            assertEquals(ArtResult.Failed("not an image"), fetch(response(type = "text/html")))
            assertEquals(ArtResult.Failed("empty"), fetch(response(bytes = ByteArray(0))))
        }

    @Test
    fun `a failed request names only the error's kind`() =
        runTest {
            val tooLarge = NetResult.Failure(NetError.TooLarge(ArtFetcher.MAX_BYTES))
            val result = ArtFetcher(StubClient { tooLarge }).fetch(URL)
            assertEquals(ArtResult.Failed("TooLarge"), result)
        }

    private companion object {
        const val URL = "https://img.example/cover.jpg"
    }
}
