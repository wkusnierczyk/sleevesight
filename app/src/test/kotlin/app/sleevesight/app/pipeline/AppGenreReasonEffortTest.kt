package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A genre's reason carries the reasoning effort it was asked at, for its credit (#1162): the level
 * chosen, or none at the scale's default (low), which the credit does not say.
 */
class AppGenreReasonEffortTest {
    private val provider = CountingProvider().apply { reply = { "Its loud guitars are rock [MB1]." } }
    private val clock = MovedClock()
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(clock = clock),
            ),
        )
    private val settings = MutableStateFlow<Settings?>(null)
    private val definitions = GenreDefinitions({ Lookup.NotFound }, GenreVocabulary.load(JvmResourceReader))

    private val track: TrackState =
        HomeFixtures.track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            "A",
                            listOf(
                                TrackLabel(
                                    GenreLabel("rock", "Rock", "rock", null, GenreLevel.ARTIST, "A"),
                                    listOf(LabelCredit(LabelSource.LASTFM, null)),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    @Test
    fun `a reason carries the effort it was asked at, none at the default`() =
        runTest {
            val cases = listOf(ReasoningEffort.MEDIUM to ReasoningEffort.MEDIUM, ReasoningEffort.LOW to null)
            for ((asked, credited) in cases) {
                settings.value = Settings(aiProvider = ProviderId.ANTHROPIC).withEffort(ProviderId.ANTHROPIC, asked)
                val notes = AppGenreNotes(definitions, parts, settings, this, ClearEpoch(), clock = clock)
                notes.explain(track, "rock")
                advanceUntilIdle()
                val shown = notes.reason(track, "rock").first() as GenreReason.Shown
                assertTrue("asked at $asked: ${shown.state}", shown.state is InsightState.Written)
                assertEquals("asked at $asked", credited, shown.effort)
            }
        }
}
