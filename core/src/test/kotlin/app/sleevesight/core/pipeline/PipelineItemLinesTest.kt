package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * A run that takes the lines the song's, the artist's and the album's evidence holds beyond the
 * track's own (#1131, `RunInput.items`): with only the player's release of its own, its insight is
 * written from them, cited as *Sources* lists them; with none, or only lines it has already, it still
 * has nothing to narrate, and makes no call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineItemLinesTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // The song's page, and the artist's, as the song's and the artist's web searches might find them.
    private val song = line(SourceId.WIKIPEDIA, "Sundown is a song by Gordon Lightfoot.")
    private val artist = line(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", Scope.ARTIST)

    // The player's own release line, as the run has it.
    private val release = line(SourceId.PLAYER, "This recording is on the release \"Sundown\".", Scope.RELEASE)

    // A run whose web search finds nothing: of its own, the player's release alone.
    private fun TestScope.pipeline(provider: FakeProvider): Pipeline {
        val empty = FakeAdapter(SourceId.WEB) { _, _ -> SourceResult(Outcome.None, emptyList(), 0.seconds) }
        return Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(empty),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }
    }

    @Test
    fun `the item lines taken are written from, numbered on from the run's own as Sources lists them`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("Sundown is a song [W1] by a Canadian singer [W2].") }

            val items = ItemLines(listOf(song, artist))

            val state = pipeline(provider).run(sundown, RunControl.Unattended(true), items = items)

            // As *Sources* numbers them after the track's own (`withAlbum`): the same ids.
            val sources = EvidenceSet.of(listOf(release), JvmHasher).with(listOf(song, artist), JvmHasher)
            assertEquals(sources.ids, state.evidence?.ids)
            assertEquals(1, provider.requests.size)
            assertIs<InsightState.Written>(state.insight)
        }

    @Test
    fun `the album's lines taken are marked as the album's, from what was taken alone`() =
        runTest {
            val albumLine = line(SourceId.WIKIPEDIA, "Sundown is his tenth album.", Scope.RELEASE)
            val provider = FakeProvider { FakeProvider.done("It is his tenth album [W1].") }
            val items = ItemLines(listOf(albumLine, song), album = listOf(albumLine))

            val state = pipeline(provider).run(sundown, RunControl.Unattended(true), items = items)

            assertEquals(setOf("W1"), state.albumIds)
        }

    @Test
    fun `no item line, or only one the run has already, leaves nothing to narrate, and no call`() =
        runTest {
            for (items in listOf(ItemLines(), ItemLines(listOf(release)))) {
                val provider = FakeProvider()

                val state = pipeline(provider).run(sundown, RunControl.Unattended(true), items = items)

                assertEquals(listOf("P1"), state.evidence?.ids)
                assertEquals(emptySet(), state.albumIds)
                assertEquals(InsightState.NoEvidence, state.insight)
                assertEquals(0, provider.requests.size)
            }
        }

    @Test
    fun `an item line of a source switched off since is not taken`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.WIKIPEDIA)
            val provider = FakeProvider()

            val state = pipeline(provider).run(sundown, RunControl.Unattended(true), items = ItemLines(listOf(song)))

            assertEquals(listOf("P1"), state.evidence?.ids)
            assertEquals(InsightState.NoEvidence, state.insight)
            assertEquals(0, provider.requests.size)
        }
}
