package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The player's release line is left out of the insight's prompt when a line about the song names
 * the release, so the model has no line to pad the insight with (#424).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineReleaseLineTest {
    private val provider = FakeProvider()

    // MusicBrainz's first-release line, naming the album "Sundown".
    private val adapters =
        listOf(
            FakeAdapter(SourceId.MUSICBRAINZ) { track, _ ->
                val first = "“${track.title}” was first released in 1974, on the album “Sundown”."
                SourceResult(Outcome.SongArticle, listOf(line(SourceId.MUSICBRAINZ, first)), Duration.ZERO)
            },
        )

    private fun TestScope.pipeline(): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = adapters,
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = ProviderId.ANTHROPIC) }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    @Test
    fun `a release the song's line names is not sent, and the track keeps its line`() =
        runTest {
            val pipeline = pipeline()
            val events = recording(pipeline)

            val state = pipeline.run(nowPlaying("Carefree Highway", "Gordon Lightfoot", "Sundown"), write = true)

            val prompt = provider.requests.single().prompt
            assertFalse("(player metadata, release)" in prompt)
            // The track block still gives the release.
            assertTrue("Release: Sundown" in prompt)
            assertEquals(listOf("MB1", "P1"), state.evidence?.ids)
            assertEquals(setOf("MB1"), assertIs<InsightState.Written>(state.insight).held)
            runCurrent()
            assertEquals(listOf(listOf("P1")), events.filterIsInstance<PipelineEvent.ModelCall>().map { it.leftOut })
        }

    @Test
    fun `a release no line names, a compilation say, is sent`() =
        runTest {
            val pipeline = pipeline()

            pipeline.run(nowPlaying("Carefree Highway", "Gordon Lightfoot", "Gord's Gold"), write = true)

            assertTrue(
                "[P1] (player metadata, release) This recording is on the release \"Gord's Gold\"." in
                    provider.requests.single().prompt,
            )
        }
}
