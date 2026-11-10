package app.sleevesight.app.pipeline

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song's, the artist's and the album's lines taken by a track that found nothing to narrate
 * (#1131), as `PipelineCoordinatorTest` runs its sequences: its run is replaced by one that takes
 * them, and its insight decided again, written without a tap only where the track would have been
 * written had there been evidence; a track with no such lines, with its own evidence, or written,
 * takes none, and nothing is paid for.
 */
class PipelineCoordinatorItemLinesTest {
    private val cases =
        listOf(
            Case(
                "nothing to narrate, then a search's lines: taken, and written as the track would have been",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                englishFinds = false
                play("A")
                settle()
                assertEquals(null, calls["A"])
                itemLines("A", "The song's page.")
                settle()
                assertEquals(listOf("A"), tookItems)
                assertEquals(listOf("The song's page."), itemsTaken.single().map { it.text })
            },
            Case(
                "under On demand: taken, Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                englishFinds = false
                play("A")
                settle()
                assertTrue(!offersWriteFor("A"))
                itemLines("A", "The song's page.")
                settle()
                assertEquals(listOf("A"), tookItems)
            },
            Case(
                "every search empty: nothing taken, no call, nothing offered",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                englishFinds = false
                play("A")
                settle()
                itemLines("A")
                settle()
                assertEquals(emptyList<String>(), tookItems)
            },
            Case(
                "the same lines found again: taken once",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                englishFinds = false
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(listOf("A"), tookItems)
            },
            Case(
                "more lines while Write is offered: taken again, Write offered, a tap writes once",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                englishFinds = false
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                itemLines("A", "The song's page.", "The artist's page.")
                settle()
                assertEquals(listOf("A", "A"), tookItems)
                assertEquals(2, itemsTaken.last().size)
                assertTrue(offersWriteFor("A"))
                tap()
                settle()
            },
            Case(
                "lines found before the run decides: taken once it finds nothing",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                englishFinds = false
                play("A")
                itemLines("A", "The song's page.")
                settle()
                assertEquals(listOf("A"), tookItems)
            },
            Case(
                "a rebuild of a track written from the lines takes them again: Rewrite offered, no call",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                acted()
                englishFinds = false
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(1, calls["A"])
                // Another model, say: the track's run is looked up again on the new pipeline.
                keyChange()
                settle()
                assertEquals(listOf("A", "A"), tookItems)
                assertEquals(listOf("The song's page."), itemsTaken.last().map { it.text })
            },
            Case(
                "a track with evidence of its own takes none",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(emptyList<String>(), tookItems)
            },
            Case(
                "a track written takes none, and is not written again",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(emptyList<String>(), tookItems)
            },
            Case(
                "another track's lines are not taken",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                englishFinds = false
                play("A")
                settle()
                itemLines("B", "B's page.")
                settle()
                assertEquals(emptyList<String>(), tookItems)
            },
            Case(
                "lines taken are carried by back online's run, which asks the web search again",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                englishFinds = false
                network(online = false)
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                network(online = true)
                settle()
                assertEquals(listOf("A", "A"), tookItems)
                // The run taking the lines asks no source again; back online's asks the web search.
                assertEquals(emptySet<SourceId>(), carried.first().asked)
                assertEquals(setOf(SourceId.WEB), carried.last().asked)
            },
        )

    @Test
    fun `item lines are taken only by a track that found nothing to narrate`() = checkAll(cases)
}
