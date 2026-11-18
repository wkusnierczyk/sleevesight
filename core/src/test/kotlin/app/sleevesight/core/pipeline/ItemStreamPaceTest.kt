package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/** The song's, the artist's and the album's insights stream at a pace (#1351), never past a sign of revision. */
class ItemStreamPaceTest {
    @Test
    fun `a sign in a piece not read still stops the item's stream when a quotation opens after it`() =
        runTest {
            // Read at 0 ms and at 210 ms only: at 210 ms a quotation is open at the end, which may yet
            // set aside what it holds, but the sign before it stays found.
            val items =
                ItemInsights(
                    AiAccess(PacedProvider(REVISED_THEN_QUOTED), Secret("k")),
                    MemoryCache(),
                    JvmHasher,
                    NameMatcher(JvmAccentFolder),
                    timeSource = testTimeSource,
                )
            val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
            val playing =
                NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = "p", duration = 200.seconds)
            val drafts = listOf(line(SourceId.MUSICBRAINZ, "Sundown came out in 1974."))
            val track =
                TrackState(
                    nowPlaying = playing,
                    normalized = Normalizer(JvmAccentFolder).normalize(playing),
                    sources =
                        mapOf(
                            SourceId.MUSICBRAINZ to
                                SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                        ),
                    evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
                )
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track, settings))
            val key = checkNotNull(items.key(InsightItem.SONG, track, evidence, settings))
            val partials = mutableListOf<String>()
            items.write(key, InsightItem.SONG, track, evidence) { state, _ ->
                if (state is InsightState.Writing) partials += state.partial
            }
            assertTrue(partials.any { it.startsWith("It came out in 1975") }, partials.toString())
            assertTrue(partials.none { "Wait" in it }, partials.toString())
        }
}
