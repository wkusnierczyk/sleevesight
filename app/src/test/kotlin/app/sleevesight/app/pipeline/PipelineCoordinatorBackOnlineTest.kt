package app.sleevesight.app.pipeline

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back online (#192), as `PipelineCoordinatorTest` runs its sequences: when the network returns,
 * the playing track's run asks again the sources it found unreachable, once per return; the
 * insight then follows *Write insights automatically*: a track not yet written may be written,
 * one written is not, without a tap.
 */
class PipelineCoordinatorBackOnlineTest {
    private val cases =
        listOf(
            Case(
                "offline, then back online: the unreachable sources are asked again, and the track written",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                assertEquals(emptyMap<String, Int>(), calls - "Zero")
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
                assertEquals(setOf(SourceId.WEB), carried.single().unreachable)
            },
            Case(
                "back online before the first action: asked again, and Write offered, not written",
                emptyMap(),
                listOf("A", "A"),
                "A",
            ) {
                network(online = false)
                play("A")
                settle()
                network(online = true)
                settle()
            },
            Case(
                "back online for a track written already: asked again, Write offered, nothing written unasked",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                offlineFinds = true
                failing = true
                play("A")
                settle()
                assertEquals(1, calls["A"])
                failing = false
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "under When I skip, a natural end asked again is offered, not written",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                acted()
                network(online = false)
                play("A")
                settle()
                network(online = true)
                settle()
            },
            Case(
                "under When I skip, a skip asked again is written",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                acted()
                network(online = false)
                play("A", ChangeCause.SKIP_IN_PLAYER)
                settle()
                network(online = true)
                settle()
            },
            Case(
                "a mode changed since Write was offered: asked again, Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                network(online = false)
                offlineFinds = true
                play("A")
                settle()
                assertTrue(offersWriteFor("A"))
                writesOn = mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "a key saved since there was no AI service: the rebuild offers Write, and asking again does not write",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                offlineFinds = true
                noAi = true
                play("A")
                settle()
                noAi = false
                keyChange()
                settle()
                assertTrue(offersWriteFor("A"))
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "a tap queued right behind the return is kept, and answers the run asking again: one call",
                mapOf("A" to 1),
                listOf("A", "A"),
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                network(online = false)
                offlineFinds = true
                play("A")
                settle()
                val offered = checkNotNull(coordinator.state.writeKey)
                send(CoordinatorEvent.NetworkChanged(true))
                offline = false
                send(CoordinatorEvent.WriteTapped(offered))
                assertEquals(listOf("A"), askedAgain)
                assertEquals(1, actions)
                settle()
                assertEquals(true, coordinator.state.ledger?.written)
                // A second tap on the stale Write does nothing more.
                tap(offered)
                settle()
            },
            Case(
                "a tap kept for a run asking again that then decides without a call answers nothing",
                emptyMap(),
                listOf("A", "A"),
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                network(online = false)
                offlineFinds = true
                play("A")
                settle()
                val offered = checkNotNull(coordinator.state.writeKey)
                // The replacement decides without a call (its AI service gone, say).
                noAi = true
                send(CoordinatorEvent.NetworkChanged(true))
                offline = false
                send(CoordinatorEvent.WriteTapped(offered))
                settle()
                assertEquals(false, coordinator.state.run?.tapped)
            },
            Case(
                "no AI service, then a key saved offline: the rebuild finding nothing never makes it write on return",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                noAi = true
                play("A")
                settle()
                noAi = false
                keyChange()
                settle()
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "nothing to narrate under Never, the mode switched since: asked again, Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                acted()
                network(online = false)
                play("A")
                settle()
                writesOn = mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "nothing to narrate, model knowledge turned on since, nothing new found: Write offered, no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                allowModel = true
                // The phone says it is back, but the web search is still unreachable.
                send(CoordinatorEvent.NetworkChanged(true))
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "asking again brings no evidence from the sources asked (a source switched on since does): no call",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                newSource = true
                send(CoordinatorEvent.NetworkChanged(true))
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "a kept tap survives a second ask-again before the decision: one call",
                mapOf("A" to 1),
                listOf("A", "A", "A"),
            ) {
                writesOn = mode(WriteAutomatically.NEVER)
                lookupMs = 2_000
                network(online = false)
                offlineFinds = true
                play("A")
                settle()
                val offered = checkNotNull(coordinator.state.writeKey)
                send(CoordinatorEvent.NetworkChanged(true))
                send(CoordinatorEvent.WriteTapped(offered))
                // The replacement's lookups go out offline, and the network flaps meanwhile.
                advance(0)
                send(CoordinatorEvent.NetworkChanged(false))
                send(CoordinatorEvent.NetworkChanged(true))
                offline = false
                settle()
                assertEquals(listOf("A", "A"), askedAgain)
                assertEquals(1, actions)
                assertEquals(true, coordinator.state.ledger?.written)
            },
            Case(
                "a kept tap for a run asking again that is served from the cache answers nothing",
                emptyMap(),
                listOf("A", "A"),
            ) {
                cache = true
                cards[key("A") to 0] = "A, kept"
                // The run offline finds no card under its service; the replacement does.
                service = 1
                writesOn = mode(WriteAutomatically.NEVER)
                network(online = false)
                offlineFinds = true
                play("A")
                settle()
                val offered = checkNotNull(coordinator.state.writeKey)
                send(CoordinatorEvent.NetworkChanged(true))
                send(CoordinatorEvent.WriteTapped(offered))
                service = 0
                offline = false
                settle()
                assertEquals(listOf("A"), cardsShown)
                assertEquals(false, coordinator.state.run?.tapped)
                assertEquals("A, kept", cardText())
            },
            Case(
                "back online with nothing unreachable does nothing",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                play("A")
                settle()
                network(online = false)
                network(online = true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
            },
            Case(
                "back online after the track changed asks again for the playing track, never the one left",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "A", "B", "B"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                play("B")
                settle()
                network(online = true)
                settle()
                assertEquals(listOf("B"), askedAgain)
            },
            Case(
                "back online after the track changed to one found in full asks nothing again",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "A", "B"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                // B is looked up online, though the coordinator has not heard of the return yet.
                offline = false
                play("B")
                settle()
                network(online = true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
            },
            Case(
                "the network back while the lookups were under way: asked again once they end",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                lookupMs = 2_000
                network(online = false)
                play("A")
                advance(DEBOUNCE_MS + 500)
                assertEquals(listOf("Zero", "A"), lookups)
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "once per return: a run asked again that finds its sources unreachable again waits for the next",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A", "A"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                send(CoordinatorEvent.NetworkChanged(true))
                // The phone says it is back, but the lookups still fail.
                settle()
                assertEquals(listOf("A"), askedAgain)
                assertEquals(listOf("Zero", "A", "A"), lookups)
                settle()
                assertEquals(listOf("A"), askedAgain)
                network(online = false)
                network(online = true)
                settle()
                assertEquals(listOf("A", "A"), askedAgain)
            },
            Case(
                "online reported again while online is no return",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                // The lookups fail though the phone says it is online (a source down, say).
                offline = true
                play("A")
                settle()
                network(online = true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
            },
            Case(
                "back online while requests are not allowed: asked again only once they are",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                hide()
                network(online = true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
                // *Look up in the background* switched on: requests are allowed, the first action kept.
                background(true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "back online while offline again before it acts: asked again at the next return",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                hide()
                network(online = true)
                network(online = false)
                background(true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
                network(online = true)
                settle()
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "back online while a model call is under way: asked again after it ends, nothing cut",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                streamMs = 10_000
                network(online = false)
                offlineFinds = true
                play("A")
                advance(DEBOUNCE_MS + 100)
                assertTrue(coordinator.state.run?.calling == true)
                network(online = true)
                assertEquals(emptyList<String>(), askedAgain)
                advance(streamMs)
                settle()
                assertEquals(emptyList<String>(), cut)
                assertEquals(listOf("A"), askedAgain)
            },
            Case(
                "a rebuild pending holds asking again; the rebuild's run looks the track up afresh",
                mapOf("Zero" to 1),
                listOf("Zero", "A", "A"),
                "A",
            ) {
                acted()
                network(online = false)
                play("A")
                settle()
                holdKeys = true
                keyChange()
                network(online = true)
                settle()
                assertEquals(emptyList<String>(), askedAgain)
                releaseKeys()
                settle()
                // A rebuild's run of a key the ledger decided offers Write (D5); the network's
                // return came before its lookups, so it asks nothing again.
                assertEquals(emptyList<String>(), askedAgain)
            },
        )

    @Test
    fun `back online asks the playing track's unreachable sources again, as the table says`() = checkAll(cases)

    @Test
    fun `asking again cancels a preparation in progress, which starts again once the run's requests are over`() {
        val world = started()
        with(world) {
            acted()
            prepareMs = 10_000
            network(online = false)
            play("A")
            settle()
            next("B")
            assertEquals(listOf("B"), preparations)
            network(online = true)
            settle()
            assertEquals(listOf("A"), askedAgain)
            assertEquals(listOf("B"), preparationsCancelled)
            assertEquals(listOf("B", "B"), preparations)
        }
    }
}
