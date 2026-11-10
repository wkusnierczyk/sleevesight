package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * A run that asks MusicBrainz again after it could not be reached (*Retry*, #724; back online,
 * #192) asks again a source that rejected a match only for want of what MusicBrainz resolves
 * (#1069, `Rejection.withoutMusicBrainz`): its verdict was reached against the failed resolution.
 * A source that rejected its match for another reason is kept, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineWithoutMusicBrainzTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val track = nowPlaying("Take California", "Propellerheads", album = "Decksandrumsandrockandroll")
    private val key = "propellerheads|take california"
    private val names = NameMatcher(JvmAccentFolder)

    private val resolver = FakeResolver(resolution = Resolution(Outcome.Unreachable))

    // MusicBrainz's adapter gives the resolution's outcome, as `MusicBrainzSource` does.
    private val musicBrainz =
        FakeAdapter(SourceId.MUSICBRAINZ) { _, resolved ->
            SourceResult(resolved?.outcome ?: Outcome.None, emptyList(), Duration.ZERO)
        }

    // Wikipedia: the song's article confirmed once MusicBrainz resolved the recording; else rejected,
    // for want of MusicBrainz when [marked].
    private fun wikipedia(marked: Boolean) =
        FakeAdapter(SourceId.WIKIPEDIA) { _, resolved ->
            if (resolved?.recordingMbid != null) {
                SourceResult(Outcome.SongArticle, listOf(line(SourceId.WIKIPEDIA, "A song.")), Duration.ZERO)
            } else {
                val reason = "“Take California” names no performer or writer credited on the track"
                SourceResult(
                    Outcome.Rejected(reason),
                    emptyList(),
                    Duration.ZERO,
                    rejections = listOf(Rejection("Take California", reason, Scope.SONG, marked)),
                )
            }
        }

    private fun TestScope.pipeline(adapters: List<SourceAdapter>): Pipeline =
        Pipeline(
            PipelineParts(normalizer, adapters, resolver, JvmHasher),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings() }

    // A control that grants every step at once and never writes.
    private fun granting() = Granted(write = false).apply { grants.values.forEach { it.complete(Unit) } }

    // Runs the track with MusicBrainz unreachable, then a *Retry* of MusicBrainz that it answers, with [other].
    private suspend fun TestScope.retried(other: FakeAdapter): TrackState {
        val pipeline = pipeline(listOf(musicBrainz, other))
        val first = granting()
        pipeline.run(track, first)
        val before = first.gathered.single()
        assertEquals(setOf(SourceId.MUSICBRAINZ), before.unreachable)
        resolver.resolution = Resolution(Outcome.SongArticle, recordingMbid = "rec-1")
        val state = pipeline.run(track, granting(), before.asking(setOf(SourceId.MUSICBRAINZ)))
        runCurrent()
        return state
    }

    @Test
    fun `a rejection for want of MusicBrainz is checked again once MusicBrainz answers a Retry`() =
        runTest {
            val wikipedia = wikipedia(marked = true)
            val state = retried(wikipedia)
            assertEquals(listOf(key, key), wikipedia.asked)
            assertEquals(Outcome.SongArticle, (state.sources[SourceId.WIKIPEDIA] as SourceState.Done).result.outcome)
        }

    @Test
    fun `back online, a rejection for want of MusicBrainz is checked again too`() =
        runTest {
            val wikipedia = wikipedia(marked = true)
            val pipeline = pipeline(listOf(musicBrainz, wikipedia))
            val first = granting()
            pipeline.run(track, first)
            resolver.resolution = Resolution(Outcome.SongArticle, recordingMbid = "rec-1")
            val state = pipeline.run(track, granting(), first.gathered.single())
            runCurrent()
            assertEquals(listOf(key, key), wikipedia.asked)
            assertEquals(Outcome.SongArticle, (state.sources[SourceId.WIKIPEDIA] as SourceState.Done).result.outcome)
        }

    // Last.fm: the artist's page verified against the resolved artist, its bio cited; with no resolution
    // (MusicBrainz not reached), nothing to check it by, so its lines are unverified, shown, never cited.
    private val lastFm =
        FakeAdapter(SourceId.LASTFM) { _, resolved ->
            val text = "The Last.fm biography of Propellerheads says: \"A duo.\""
            val bio = listOf(line(SourceId.LASTFM, text, Scope.ARTIST))
            if (resolved?.outcome == Outcome.SongArticle) {
                SourceResult(Outcome.ArtistArticle, bio, Duration.ZERO)
            } else {
                SourceResult(Outcome.ArtistArticle, emptyList(), Duration.ZERO, unverified = bio)
            }
        }

    @Test
    fun `lines left unverified for want of MusicBrainz are judged again once it answers, and the artist has them`() =
        runTest {
            val state = retried(lastFm)
            assertEquals(listOf(key, key), lastFm.asked)
            val result = (state.sources[SourceId.LASTFM] as SourceState.Done).result
            assertEquals(1, result.evidence.size)
            // Wikipedia off, AI web search off: the artist's insight has the bio to write from (#1163).
            val settings =
                Settings(
                    aiProvider = ProviderId.ANTHROPIC,
                    aiWebSearch = AiWebSearch.OFF,
                    enabledSources = Settings().enabledSources - SourceSwitch.WIKIPEDIA,
                )
            val items = ItemInsights(AiAccess(FakeProvider(), Secret("k")), MemoryCache(), JvmHasher, names)
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, state, settings))
            assertEquals(1, evidence.evidence.lines.size)
            assertTrue(evidence.unanswered.isEmpty())
        }

    @Test
    fun `a rejection for another reason is kept as it was`() =
        runTest {
            val wikipedia = wikipedia(marked = false)
            val state = retried(wikipedia)
            assertEquals(listOf(key), wikipedia.asked)
            assertIs<Outcome.Rejected>((state.sources[SourceId.WIKIPEDIA] as SourceState.Done).result.outcome)
        }
}
