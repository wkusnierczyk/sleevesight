package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What *Read aloud* says of an insight (#325): its sentences as the card shows them, without their
 * citations, each sentence the sources do not back (as the grounding check marked it) said to be
 * unverified; and the track line, by the track's cleaned-up title and artists.
 */
class SpokenWordsTest {
    private val words = SpokenWords("Unverified:") { title, artists -> "$title, by $artists." }

    private fun spoken(
        reply: String,
        allowModel: Boolean = false,
    ) = spokenSentences(written(reply, allowModel = allowModel).grounded, words)

    @Test
    fun eachSentenceIsReadWithoutItsCitations() {
        assertEquals(
            listOf("Sundown was recorded in 1973.", "It topped the charts, and it is a single."),
            spoken("Sundown was recorded in 1973 [MB1]. It topped the charts [W1], and it is a single [WD1]."),
        )
    }

    @Test
    fun anUncitedSentenceIsSaidToBeUnverified() {
        assertEquals(
            listOf("Sundown was recorded in 1973.", "Unverified: It was a hit in Canada."),
            spoken("Sundown was recorded in 1973 [MB1]. It was a hit in Canada."),
        )
    }

    @Test
    fun aSentenceFromTheModelsOwnKnowledgeIsSaidToBeUnverifiedWithoutItsMark() {
        val grounded =
            written("Sundown was recorded in 1973 [MB1]. Lightfoot wrote it in Toronto [AI].", allowModel = true)
        // As the check marked it, not as the text reads.
        assertEquals(
            SentenceStatus.MODEL,
            grounded.grounded.paragraphs
                .single()[1]
                .status,
        )
        assertEquals(
            listOf("Sundown was recorded in 1973.", "Unverified: Lightfoot wrote it in Toronto."),
            spokenSentences(grounded.grounded, words),
        )
    }

    @Test
    fun theMarkOfAnInsightKeptFromBeforeIsLeftOutTooAndACitationOfNoLineIsUnverified() {
        // `[M]`, the model's mark until #668, in an insight kept from before.
        assertEquals(
            listOf("Sundown was recorded in 1973.", "Unverified: Lightfoot wrote it in Toronto."),
            spoken("Sundown was recorded in 1973 [MB1]. Lightfoot wrote it in Toronto [M].", allowModel = true),
        )
        assertEquals(listOf("Unverified: It was a hit."), spoken("It was a hit [W9]."))
    }

    @Test
    fun aStatedGapIsReadAsItIsWithoutAWarning() {
        val grounded = written("Sundown was recorded in 1973 [MB1]. Little else is documented.").grounded
        assertEquals(SentenceStatus.HEDGE, grounded.paragraphs.single()[1].status)
        assertEquals(
            listOf("Sundown was recorded in 1973.", "Little else is documented."),
            spokenSentences(grounded, words),
        )
    }

    @Test
    fun markdownIsReadAsTheCardShowsItAndASentenceShowingNothingIsNotRead() {
        assertEquals(
            listOf("The album Sundown was recorded in 1973.", "It topped the charts."),
            spoken("The album *Sundown* was recorded in 1973 [MB1].\n\n---\n\nIt **topped** the charts [W1]."),
        )
    }

    @Test
    fun paragraphsAreReadInOrder() {
        assertEquals(
            listOf("Sundown was recorded in 1973.", "It topped the charts."),
            spoken("Sundown was recorded in 1973 [MB1].\n\nIt topped the charts [W1]."),
        )
    }

    @Test
    fun aTextWithNoSentencesIsReadWholeWithoutItsCitations() {
        val grounded = GroundedText("Sundown [MB1] ", emptyList(), allowModel = false)
        assertEquals(listOf("Sundown"), spokenSentences(grounded, words))
    }

    @Test
    fun theTrackLineNamesTheTitleAndArtistsAsCleanedUp() {
        val remaster = sundown.copy(title = "Sundown - 2006 Remaster", artists = "Gordon Lightfoot")
        assertEquals("Sundown, by Gordon Lightfoot.", spokenTrackLine(track(remaster), words))
    }
}
