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
                "a write from the lines that fails keeps them: a rebuild takes them again, Write offered, no call",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                acted()
                englishFinds = false
                play("A")
                settle()
                failing = true
                itemLines("A", "The song's page.")
                settle()
                failing = false
                // The key or model fixed: the track's run is looked up again on the new pipeline.
                keyChange()
                settle()
                assertEquals(listOf("A", "A"), tookItems)
            },
            Case(
                "a write from the lines that is stopped keeps them: a rebuild takes them again, Write offered, no call",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                acted()
                englishFinds = false
                streamMs = 1_000
                play("A")
                settle()
                itemLines("A", "The song's page.")
                assertEquals(1, calls["A"])
                stopTap()
                settle()
                assertEquals(listOf("A"), stopped)
                keyChange()
                settle()
                assertEquals(listOf("A", "A"), tookItems)
            },
            Case(
                "offline, the lines wait for the network: none taken, no call; back online, written once",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
            ) {
                acted()
                englishFinds = false
                network(online = false)
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(emptyList<String>(), tookItems)
                assertEquals(null, calls["A"])
                network(online = true)
                settle()
                assertEquals(listOf("A"), tookItems)
            },
            Case(
                "a track written only on a tap (before the first action): taken, Write offered, no call",
                emptyMap(),
                listOf("A", "A"),
                "A",
            ) {
                englishFinds = false
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                assertEquals(listOf("A"), tookItems)
            },
            Case(
                "a track that came while Saver paused writing: taken, Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "B", "B"),
                "B",
            ) {
                pause(1)
                acted()
                background(true)
                hide()
                englishFinds = false
                play("A")
                settle()
                play("B")
                settle()
                itemLines("B", "B's page.")
                settle()
                assertEquals(listOf("B"), tookItems)
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
                "lines taken are carried by back online's run, which asks the unreachable source again",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                englishFinds = false
                // Online, Wikipedia down on its own: the lines are taken, Wikipedia still unreachable.
                wikipediaDown = true
                play("A")
                settle()
                itemLines("A", "The song's page.")
                settle()
                wikipediaDown = false
                network(online = false)
                network(online = true)
                settle()
                assertEquals(listOf("A", "A"), tookItems)
                // The run taking the lines asks no source again; back online's asks Wikipedia.
                assertEquals(emptySet<SourceId>(), carried.first().asked)
                assertEquals(setOf(SourceId.WIKIPEDIA), carried.last().asked)
            },
        )

    @Test
    fun `item lines are taken only by a track that found nothing to narrate`() = checkAll(cases)
}
