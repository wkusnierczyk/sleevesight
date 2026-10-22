package app.sleevesight.cli

import app.sleevesight.net.NetBody
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureHttpTest {
    private val root: File = Files.createTempDirectory("fixtures").toFile()

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    private class Live(
        private val body: String,
    ) : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            return NetResult.Success(NetResponse(200, emptyMap(), body.encodeToByteArray()))
        }

        override fun close() = Unit
    }

    private val search =
        NetRequest(
            "https://musicbrainz.org/ws/2/recording",
            query = mapOf("query" to "recording:\"Sundown\"", "limit" to "50", "fmt" to "json"),
        )

    @Test
    fun `a recorded exchange replays, MusicBrainz without its tags, and the index is record_py's`() =
        runBlocking {
            val store = FixtureStore(root)
            val live = Live("""{"recordings":[{"id":"r1","tags":[{"name":"folk"}],"genres":[]}]}""")
            RecordingNetClient(live, store, setOf("musicbrainz")).send(search)
            store.save()

            val replayed = ReplayNetClient(FixtureStore(root)).send(search)
            val body = assertIs<NetResult.Success<NetResponse>>(replayed).value.body
            assertFalse("tags" in body || "genres" in body)
            assertTrue("\"r1\"" in body)
            val index = File(root, "musicbrainz/index.json").readText()
            assertTrue("\"file\": \"recording-recording-sundown.json\"" in index, index)
        }

    @Test
    fun `a POST is matched by its body, and other sources replay while one records`() =
        runBlocking {
            val store = FixtureStore(root)
            val live = Live("data: {}\n")
            val recorder = RecordingNetClient(live, store, setOf("anthropic"))
            val post = { prompt: String ->
                NetRequest(
                    "https://api.anthropic.com/v1/messages",
                    method = NetMethod.POST,
                    secrets = NetSecrets(headers = mapOf("x-api-key" to "sk-ant-secret")),
                    body = NetBody("""{"prompt":"$prompt"}""", "application/json"),
                )
            }
            recorder.stream(post("a")) {}
            assertIs<NetResult.Failure>(recorder.send(search))
            store.save()

            val replay = ReplayNetClient(FixtureStore(root))
            assertIs<NetResult.Success<NetResponse>>(replay.send(post("a")))
            assertIs<NetResult.Failure>(replay.send(post("b")))
            assertEquals(1, replay.misses.size)
            val written = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            assertFalse("sk-ant-secret" in written)
        }

    @Test
    fun `a Google stream is recorded with its key left out and its response id replaced`() =
        runBlocking {
            val store = FixtureStore(root)
            val reply = "data: {\"candidates\":[],\"responseId\":\"abc-123\"}\n"
            val recorder = RecordingNetClient(Live(reply), store, setOf("google"))
            val post =
                NetRequest(
                    "https://generativelanguage.googleapis.com/v1/models/gemini-3.5-flash-lite:streamGenerateContent",
                    method = NetMethod.POST,
                    query = mapOf("alt" to "sse"),
                    secrets = NetSecrets(headers = mapOf("x-goog-api-key" to "AIza-secret")),
                    body = NetBody("""{"contents":[]}""", "application/json"),
                )
            recorder.stream(post) {}
            store.save()

            assertIs<NetResult.Success<NetResponse>>(ReplayNetClient(FixtureStore(root)).send(post))
            val written = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            assertFalse("AIza-secret" in written)
            assertFalse("abc-123" in written)
            assertTrue("\"responseId\":\"resp_recorded\"" in written, written)
        }

    @Test
    fun `a MusicBrainz body that cannot be read is not recorded, and is reported`() =
        runBlocking {
            val store = FixtureStore(root)
            val recorder = RecordingNetClient(Live("<html>busy</html>"), store, setOf("musicbrainz"))
            recorder.send(search)
            store.save()

            assertFalse(File(root, "musicbrainz").exists())
            assertTrue(recorder.misses.single().endsWith("(not recorded: not JSON)"), recorder.misses.toString())
        }

    @Test
    fun `a keyless source's request carrying a key is refused`() {
        val recorder = RecordingNetClient(Live("{}"), FixtureStore(root), setOf("wikipedia"))
        val keyed =
            NetRequest(
                "https://en.wikipedia.org/w/api.php",
                secrets = NetSecrets(query = mapOf("token" to "t")),
            )
        assertFailsWith<IllegalStateException> { runBlocking { recorder.send(keyed) } }
    }
}
