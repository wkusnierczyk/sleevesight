package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.Pipeline
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.wiring.pipelineWiringOn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Recording a paid call (`record --record anthropic`) through the provider and the pipeline: the
 * transport stays open after `message_stop` until its end, and the reply is recorded only then, so
 * the pipeline must read the stream to its end rather than close it at the reply's end (#163).
 */
class RecordingStreamTest {
    private val root: File = Files.createTempDirectory("fixtures").toFile()

    @AfterTest
    fun clean() {
        root.deleteRecursively()
    }

    // The live service: the reply's events, then the transport held open until [eof].
    private class Live : NetClient {
        val held = CompletableDeferred<Unit>()
        val eof = CompletableDeferred<Unit>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> = error("Only streamed here")

        override suspend fun stream(
            request: NetRequest,
            onLine: suspend (String) -> Unit,
        ): NetResult<NetResponse> {
            REPLY.lines().forEach { onLine(it) }
            held.complete(Unit)
            eof.await()
            return NetResult.Success(NetResponse(200, emptyMap(), ByteArray(0)))
        }

        override fun close() = Unit
    }

    // One line found, so the pipeline has something to narrate and makes the call (#193).
    private object OneLine : SourceAdapter {
        override val id = SourceId.WIKIPEDIA

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ) = SourceResult(
            Outcome.SongArticle,
            listOf(EvidenceDraft(id, Scope.SONG, "It was a single.", null, Instant.parse("2026-10-18T00:00:00Z"))),
            Duration.ZERO,
        )
    }

    @Test
    fun `a reply streamed to the transport's end is recorded`() =
        runBlocking {
            val store = FixtureStore(root)
            val live = Live()
            val recorder = RecordingNetClient(live, store, setOf("anthropic"))
            val provider =
                pipelineWiringOn(
                    recorder,
                    JvmAccentFolder,
                    JvmResourceReader,
                ).aiProviders.getValue(ProviderId.ANTHROPIC)
            val pipeline =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(OneLine),
                        resolver = TrackResolver { _, _ -> Resolution.NONE },
                        hasher = JvmHasher,
                        ai = AiAccess(provider, Secret("sk-ant-test")),
                    ),
                ) { Settings(aiProvider = ProviderId.ANTHROPIC) }
            val playing = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", album = "Sundown", player = "cli")
            var insight: InsightState? = null
            val run = launch { insight = pipeline.run(playing, write = true).insight }

            // A run that makes no call fails here rather than hanging the build.
            withTimeout(HELD_WITHIN) { live.held.await() }
            // The reply's end is in; the run reads on until the transport ends.
            repeat(SPINS) { yield() }
            assertTrue(run.isActive)
            live.eof.complete(Unit)
            run.join()
            store.save()

            assertIs<InsightState.Written>(insight)
            val recorded = File(root, "anthropic").listFiles { file -> file.extension == "sse" }.orEmpty()
            assertTrue(recorded.single().readText().contains("message_stop"))
        }

    private companion object {
        const val SPINS = 20
        val HELD_WITHIN = 30.seconds

        val REPLY =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_live","usage":{"input_tokens":10,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It was a single [W1]."}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}

            event: message_stop
            data: {"type":"message_stop"}
            """.trimIndent() + "\n"
    }
}
