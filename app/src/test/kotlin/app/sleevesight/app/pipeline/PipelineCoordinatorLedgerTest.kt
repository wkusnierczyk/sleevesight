package app.sleevesight.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The insight ledger's rules (#51; the sixth to ninth review rounds of PR #155), as
 * `PipelineCoordinatorTest` runs its sequences: a written track's new key, rebuilds and the card
 * paid for, its rewrite, and its lifetime.
 */
class PipelineCoordinatorLedgerTest {
    private val cases =
        listOf(
            Case(
                "a held new length, then a rebuild changing nothing in the card's key: the card shown, nothing again",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                cache = true
                play("A", seconds = 106)
                settle()
                tap()
                settle()
                play("A", seconds = 279)
                settle()
                keyChange()
                settle()
                assertEquals(listOf("A"), cardsShown)
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "a rebuild changing the card's key, then a new length: a rewrite offered, nothing unasked",
                mapOf("A" to 1),
                listOf("A"),
                "A",
            ) {
                cache = true
                play("A", seconds = 106)
                settle()
                tap()
                settle()
                service++
                keyChange()
                settle()
                play("A", seconds = 280)
                settle()
                assertTrue(coordinator.state.rewrites())
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "a written track whose card is not found is never written without a tap, the first action made",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                play("A")
                settle()
                keyChange()
                settle()
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "a card kept, shown, then a new length: held, with no lookup or call",
                emptyMap(),
                listOf("A"),
            ) {
                cache = true
                cards[key("A", 106) to service] = "A kept"
                play("A", seconds = 106)
                settle()
                play("A", seconds = 279)
                settle()
                assertEquals(listOf("A"), cardsShown)
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "the header follows a held report: the artists filled in, the insight kept",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                report(nowPlaying("A", mediaId = "m1"))
                settle()
                tap()
                settle()
                report(nowPlaying("A", artists = "Artist & Guest", mediaId = "m1"))
                settle()
                assertEquals(
                    "Artist & Guest",
                    coordinator.state.ledger
                        ?.shown
                        ?.artists,
                )
                assertEquals(
                    "Artist",
                    coordinator.state.run
                        ?.nowPlaying
                        ?.artists,
                )
            },
            Case(
                "back to a written track after another settled is a track change, decided afresh",
                mapOf("Zero" to 1, "A" to 2),
                listOf("Zero", "A", "B", "A"),
            ) {
                // B settles, so the ledger is emptied, though B is skipped before it decides.
                // Without the cache here; in the app the card kept is shown, with no call.
                acted()
                play("A")
                settle()
                lookupMs = 2_000
                play("B")
                advance(DEBOUNCE_MS + 500)
                play("A")
                settle()
            },
            Case(
                "a rewrite's card replaces the one before in the ledger, and survives a rebuild",
                mapOf("A" to 2),
                listOf("A", "A", "A"),
                "A",
            ) {
                play("A")
                settle()
                tap()
                settle()
                assertEquals("A #1", cardText())
                keyChange()
                settle()
                tap()
                settle()
                assertEquals("A #2", cardText())
                // The card stays through another rebuild.
                keyChange()
                settle()
                assertEquals("A #2", cardText())
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "the card goes with its ledger entry: another track settled, the card is gone",
                mapOf("A" to 1, "B" to 1),
                listOf("A", "B"),
            ) {
                play("A")
                settle()
                tap()
                settle()
                assertEquals("A #1", cardText())
                lookupMs = 2_000
                play("B")
                advance(DEBOUNCE_MS + 100)
                assertEquals(null, cardText())
                // The first action made (the tap), B is written without one.
                settle()
                assertEquals("B #1", cardText())
            },
            Case(
                "a reply kept as NO_EVIDENCE is written: a new length after it is held, with no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
            ) {
                cache = true
                acted()
                cards[key("A", 106) to service] = null
                play("A", seconds = 106)
                settle()
                assertEquals(true, coordinator.state.ledger?.written)
                play("A", seconds = 279)
                settle()
            },
            Case(
                "the newest paid card survives a later cache hit of an older one",
                mapOf("A" to 2),
                listOf("A"),
                "A",
            ) {
                cache = true
                play("A")
                settle()
                tap()
                settle()
                // Another service: no card under it, so Write offered beside A #1; a tap writes A #2.
                service = 1
                keyChange()
                settle()
                tap()
                settle()
                assertEquals("A #2", cardText())
                // Back to the first service: the cache serves A #1, which does not replace A #2.
                service = 0
                keyChange()
                settle()
                assertEquals(listOf("A"), cardsShown)
                assertEquals("A #2", cardText())
                // And a service with no card at all: the card shown beside Write is A #2.
                service = 2
                keyChange()
                settle()
                assertEquals("A #2", cardText())
            },
            Case(
                "a run's report after another track settled leaves the emptied ledger empty",
                mapOf("A" to 1),
                listOf("A", "B"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(1_000)
                // A rebuild waits for the call; B settles meanwhile, emptying the ledger.
                keyChange()
                lookupMs = 5_000
                play("B")
                advance(DEBOUNCE_MS + 100)
                assertEquals(null, coordinator.state.ledger)
                // A's call ends and reports its card: ignored. The swap then starts B.
                advance(9_000)
                assertEquals(null, coordinator.state.ledger)
                assertEquals(
                    "B",
                    coordinator.state.run
                        ?.nowPlaying
                        ?.title,
                )
            },
            Case(
                "an old run's call ending before its track's return settles brings nothing back",
                mapOf("A" to 2),
                listOf("A", "A"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(1_000)
                // A rebuild waits for the call; B settles meanwhile, orphaning A's run.
                keyChange()
                play("B")
                advance(DEBOUNCE_MS + 100)
                // A returns; the call ends (and the swap happens) before the return settles.
                advance(8_000)
                play("A")
                advance(400)
                // Swapped in, with no run yet: the return has not settled.
                assertEquals(null, coordinator.state.run)
                assertEquals(null, coordinator.state.ledger)
                // The return settles: a run, and a decision, of its own (written, the first action made).
                advance(30_000)
                assertEquals("A #2", cardText())
            },
            Case(
                "an old run's call ending after its track's return settled brings nothing back",
                mapOf("A" to 2),
                listOf("A", "A"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(1_000)
                keyChange()
                play("B")
                advance(DEBOUNCE_MS + 100)
                // A returns and settles while the old call still runs: it holds nothing.
                play("A")
                advance(DEBOUNCE_MS + 100)
                assertEquals(null, coordinator.state.ledger)
                // The call ends: its card is ignored; the swap starts the return's own run.
                advance(30_000)
                assertEquals("A #2", cardText())
            },
            // A call that failed, and Stop (#163): the ledger stays written, the card paid for kept.
            Case(
                "a rewrite stopped keeps the card paid for, and offers Rewrite again",
                mapOf("A" to 2),
                listOf("A", "A"),
                "A",
            ) {
                play("A")
                settle()
                tap()
                settle()
                service++
                keyChange()
                settle()
                streamMs = 10_000
                tap()
                advance(2_000)
                stopTap()
                settle()
                assertEquals(listOf("A"), stopped)
                assertEquals("A #1", cardText())
                assertTrue(coordinator.state.rewrites())
            },
            Case(
                "a rewrite that fails keeps the card paid for; a tap rewrites it",
                mapOf("A" to 3),
                listOf("A", "A"),
            ) {
                play("A")
                settle()
                tap()
                settle()
                service++
                keyChange()
                settle()
                failing = true
                tap()
                settle()
                assertEquals("A #1", cardText())
                assertTrue(coordinator.state.rewrites())
                failing = false
                tap()
                settle()
                assertEquals("A #3", cardText())
            },
            Case(
                "back to a track whose call was stopped, after another settled, is a new decision",
                mapOf("Zero" to 1, "A" to 2, "B" to 1),
                listOf("Zero", "A", "B", "A"),
            ) {
                acted()
                streamMs = 10_000
                play("A")
                settle()
                stopTap()
                streamMs = 0
                play("B")
                settle()
                play("A")
                settle()
            },
        )

    @Test
    fun eachLedgerSequenceGrantsOnlyWhatTheRulesAllow() = checkAll(cases)
}
