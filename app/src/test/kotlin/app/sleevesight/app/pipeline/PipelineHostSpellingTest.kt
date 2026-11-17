package app.sleevesight.app.pipeline

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

/**
 * A spelling the spelling check found and MusicBrainz confirmed (#1293), on the host: the track is
 * looked up again with it, its resolution used, and again when it plays later; no longer than *Keep
 * history for* after the check's answer was written, shortened past it included.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostSpellingTest : PipelineHostFixture() {
    private val confirmed = Resolution(Outcome.SongArticle, recordingMbid = "rec-sundown")

    private fun foundAs() =
        FoundAs(
            "Sundown",
            "Artist",
            Normalizer(JvmAccentFolder).normalize(checkNotNull(changes.value).nowPlaying),
            "Claude Haiku 5.5",
            confirmed,
            writtenAt = clock.at,
        )

    // Sundwon played, MusicBrainz finding nothing, then found as Sundown.
    private fun TestScope.found() {
        resolutions += Resolution(Outcome.None)
        started()
        play("Sundwon")
        settle()
        host.spellingFound(checkNotNull(changes.value).nowPlaying, foundAs())
        settle()
    }

    private fun shownAs() =
        host.state.value.track
            ?.foundAs

    @Test
    fun `the track is looked up again with the spelling, its resolution used, and again when played later`() =
        runTest {
            found()
            assertEquals("Sundown", shownAs()?.title)
            assertEquals(
                "Sundwon",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
            assertEquals(
                confirmed,
                host.state.value.track
                    ?.resolution,
            )
            assertTrue(resolved.none { it.first.endsWith("sundown") })
            play("Carefree Highway")
            settle()
            play("Sundwon")
            settle()
            assertEquals("Sundown", shownAs()?.title)
        }

    @Test
    fun `Keep history for shortened past the check's answer looks the track up again without it`() =
        runTest {
            found()
            assertEquals("Sundown", shownAs()?.title)
            clock.at += 8.days
            settings.value = settings.value.copy(historyLifetime = 7.days)
            runCurrent()
            settle()
            assertNull(shownAs())
            play("Carefree Highway")
            settle()
            play("Sundwon")
            settle()
            assertNull(shownAs())
        }
}
