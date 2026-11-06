package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InLanguageState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What *Sources* says of the lookups in the track's language (#902) besides their rows: the offline
 * line and the notes count them, and their rejected matches are listed, marked with the language.
 */
class InLanguageModelTest {
    // The English sources landed, web search with nothing; the lookups in Hindi as given.
    private val english = track().sources + (SourceId.WEB to done(Outcome.None))

    private fun searched(
        wikipedia: SourceState = done(),
        web: SourceState = done(),
    ): TrackState =
        track(sources = english)
            .copy(inLanguage = InLanguageState("hi", mapOf(SourceId.WIKIPEDIA to wikipedia, SourceId.WEB to web)))

    private fun notes(
        track: TrackState,
        offline: Boolean,
    ) = sourceNotes(sourceChips(track, emptySet(), modelAllowed = false), offline)

    @Test
    fun offlineWithOnlyTheWebSearchInHindiUnreachableShowsTheOfflineLineAndNoNote() {
        val track = searched(web = done(Outcome.Unreachable))
        assertTrue(offlineShown(PipelineView(offline = true), track))
        assertEquals(emptyList<SourceNote>(), notes(track, offline = true))
    }

    @Test
    fun onlineTheWebSearchInHindiUnreachableHasItsNoteAndNoOfflineLine() {
        val track = searched(web = done(Outcome.Unreachable))
        assertFalse(offlineShown(PipelineView(offline = false), track))
        assertEquals(listOf(SourceNote.Unreachable(SourceId.WEB, "hi")), notes(track, offline = false))
    }

    @Test
    fun wikipediaInHindiThatFailedHasItsNote() {
        val track = searched(wikipedia = SourceState.Failed("IllegalStateException"))
        assertEquals(listOf(SourceNote.Failed(SourceId.WIKIPEDIA, "hi")), notes(track, offline = false))
    }

    @Test
    fun aMatchTheWebSearchInHindiRejectedIsListedWithItsLanguageAfterTheEnglishOnes() {
        val rejected = done(rejections = listOf(Rejection("Sundown", "another song", Scope.SONG)))
        val track =
            searched(web = done(Outcome.Rejected("not about the recording")))
                .copy(sources = english + (SourceId.WEB to rejected))
        assertEquals(
            listOf(
                RejectedMatch(SourceId.WEB, "Sundown", "another song"),
                RejectedMatch(SourceId.WEB, null, "not about the recording", "hi"),
            ),
            rejectedMatches(track),
        )
    }
}
