package app.sleevesight.app.ui.home

import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.SentenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The runs of dimmed sentences, their marks, and the note's lines in order (#1437). */
class DimmedRunsTest {
    @Test
    fun aRunEndsWhereTheBackingChanges() {
        val s2 = listOf("S2")
        val backings = listOf(null, listOf("S1"), s2, listOf("S2"), UNCITED_RUN, UNCITED_RUN, listOf("S3"))
        assertEquals(listOf(false, true, false, true, false, true, true), runEnds(backings))
    }

    @Test
    fun reorderedOrRepeatedCitationsOfTheSameLinesAreOneRun() {
        fun unsupported(vararg cites: String) =
            CheckedSentence("It sold well.", cites.toList(), SentenceStatus.UNSUPPORTED)
        val sentences =
            listOf(
                unsupported("S1", "S2"),
                unsupported("S2", "S1"),
                unsupported("S2", "S2", "S1"),
                unsupported("S1", "S1"),
                unsupported("S1"),
            )
        val backings = sentences.map(::backing)
        assertEquals(listOf(false, false, true, false, true), runEnds(backings))
    }

    @Test
    fun theNotesLinesAreSortedByLetterThenNumber() =
        assertEquals(listOf("MB1", "S2", "S8", "S10", "W2"), sortedIds(listOf("S8", "W2", "S10", "MB1", "S2")))

    @Test
    fun aSentenceWithoutItsChipsKeepsNoSpaceBeforeItsStop() {
        val runs = listOf(ShownRun.Text("It sold "), ShownRun.Cite("S2"), ShownRun.Text("."))
        assertEquals(listOf(ShownRun.Text("It sold"), ShownRun.Text(".")), withoutChips(runs))
        val mid = listOf(ShownRun.Text("It sold "), ShownRun.Cite("S2"), ShownRun.Text(" well."))
        assertEquals(listOf(ShownRun.Text("It sold"), ShownRun.Text(" well.")), withoutChips(mid))
    }
}
