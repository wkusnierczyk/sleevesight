package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The coordinator's rules (#51; D5, D6), pure: each sequence from the reviews of PR #155 runs
 * through [PipelineCoordinator] in a small simulated world, which carries out its effects as the
 * host and the runs would (timers, builds, lookups that take a while, model calls that stream),
 * in virtual time, with no threads. Each case states the model calls per track, the tracks whose
 * lookups were granted, and the track Write is offered for at the end.
 */
class PipelineCoordinatorTest {
    private val cases =
        listOf(
            Case(
                "a change waits for the debounce, and only the latest of a burst runs",
                emptyMap(),
                listOf("Two"),
                "Two",
            ) {
                play("One")
                advance(300)
                play("Two")
                settle()
            },
            Case("a change still in its debounce when the app is hidden runs on return", emptyMap(), listOf("A"), "A") {
                play("A")
                advance(100)
                hide()
                settle()
                assertEquals(emptyList<String>(), lookups)
                show()
                settle()
            },
            Case(
                "nothing is looked up while hidden, and the visible track catches up",
                emptyMap(),
                listOf("One", "Three"),
                "Three",
            ) {
                play("One")
                settle()
                hide()
                play("Two")
                settle()
                play("Three")
                settle()
                show()
                settle()
            },
            Case(
                "lookups under way finish when hidden, and the write waits for background lookups",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                lookupMs = 2_000
                play("A")
                advance(DEBOUNCE_MS + 1_000)
                hide()
                settle()
                assertEquals(mapOf("Zero" to 1), calls)
                background(true)
                settle()
            },
            Case(
                "a model call under way when a key changes is the only one paid; a rewrite only on a tap",
                mapOf("A" to 2),
                listOf("A", "A"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(2_000)
                keyChange()
                advance(30_000)
                assertEquals(emptyList<String>(), cut)
                // The replay finds no card (the simulated world keeps none here): Write offered
                // beside the card paid for, to rewrite it.
                assertEquals(mapOf("A" to 1), calls)
                assertTrue(coordinator.state.rewrites())
                tap()
                advance(30_000)
            },
            Case("a rotation keeps the first action", mapOf("Zero" to 1, "B" to 1), listOf("Zero", "B")) {
                acted()
                send(CoordinatorEvent.ActivityStopped(changingConfigurations = true))
                show()
                play("B")
                settle()
            },
            Case(
                "Write, hide, another track, show: the first action stands, and the track is written on return",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                acted()
                hide()
                play("B")
                settle()
                assertEquals(listOf("Zero"), lookups)
                show()
                settle()
            },
            Case(
                "a return from the background keeps the first action (#190)",
                mapOf("Zero" to 1, "B" to 1, "C" to 1),
                listOf("Zero", "B", "C"),
            ) {
                // The user switches to the player to skip, and comes back.
                acted()
                hide()
                play("B", ChangeCause.SKIP_IN_PLAYER)
                advance(100)
                show()
                settle()
                play("C")
                settle()
            },
            Case(
                "the screen turned off and on keeps the first action (#190)",
                mapOf("Zero" to 1, "A" to 1, "B" to 1),
                listOf("Zero", "A", "B"),
            ) {
                // Off and on is a stop and a start that are not a configuration change, a track
                // ending while the screen is off included.
                acted()
                hide()
                show()
                play("A")
                settle()
                hide()
                play("B")
                settle()
                show()
                settle()
            },
            Case("a newer track's action does not authorise the older run's write", mapOf("B" to 1), listOf("A", "B")) {
                lookupMs = 2_000
                play("A")
                advance(DEBOUNCE_MS + 1_500)
                play("B", ChangeCause.USER_ACTION)
                advance(DEBOUNCE_MS - 50)
                assertEquals(emptyMap<String, Int>(), calls)
                settle()
            },
            Case(
                "returning during a newer track's debounce does not resume the older one",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                acted()
                hide()
                play("A")
                settle()
                play("B")
                advance(100)
                show()
                settle()
            },
            Case(
                "background lookups turned on during a newer track's debounce write only the newer track",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                acted()
                hide()
                play("A")
                settle()
                play("B")
                advance(100)
                background(true)
                settle()
            },
            Case(
                "a rebuild while a track gathers still writes it, once",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                lookupMs = 2_000
                play("A")
                advance(DEBOUNCE_MS + 500)
                keyChange()
                settle()
            },
            Case(
                "a rebuild offers no Write, and takes no tap, until the new pipeline does",
                emptyMap(),
                listOf("A", "A"),
                "A",
            ) {
                play("A")
                settle()
                assertTrue(offersWriteFor("A"))
                holdKeys = true
                keyChange()
                assertEquals(false, offersWriteFor("A"))
                tap()
                releaseKeys()
                settle()
            },
            Case(
                "a return during a rebuild keeps the first action",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "Zero", "B"),
            ) {
                acted()
                holdKeys = true
                keyChange()
                hide()
                show()
                releaseKeys()
                settle()
                play("B")
                settle()
            },
            Case(
                "a stale run made current again while hidden starts no model call",
                mapOf("Zero" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                lookupMs = 2_000
                play("A")
                advance(DEBOUNCE_MS + 1_800)
                play("B")
                advance(300)
                hide()
                play("A")
                settle()
            },
            Case(
                "consecutive rebuilds keep the track written, write it once, and offer a rewrite",
                mapOf("A" to 1),
                listOf("A", "A", "A"),
                "A",
            ) {
                play("A")
                settle()
                tap()
                settle()
                lookupMs = 2_000
                keyChange()
                advance(500)
                keyChange()
                advance(10_000)
                assertTrue(coordinator.state.rewrites())
            },
            Case(
                "a rebuild in a newer track's debounce while the old track streams waits for the call",
                mapOf("A" to 1, "B" to 1, "C" to 1),
                // B once the call ends, and again after the second rebuild, which writes nothing.
                listOf("A", "B", "B", "C"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(2_000)
                play("B")
                advance(100)
                keyChange()
                advance(60_000)
                keyChange()
                play("C")
                advance(60_000)
            },
            Case(
                "a skip while streaming hidden, then a rebuild, goes on when shown",
                mapOf("A" to 1, "B" to 1),
                listOf("A", "B"),
            ) {
                streamMs = 10_000
                play("A")
                settle()
                tap()
                advance(1_000)
                hide()
                play("B")
                settle()
                keyChange()
                advance(1_000)
                show()
                advance(60_000)
            },
            Case(
                "a skip there and back within the debounce lets the run go on",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A"),
            ) {
                acted()
                lookupMs = 1_200
                play("A")
                advance(DEBOUNCE_MS + 1_000)
                play("B")
                advance(550)
                assertEquals(mapOf("Zero" to 1), calls)
                play("A")
                settle()
            },
            Case("a pipeline that cannot be built runs nothing until the next rebuild", emptyMap(), listOf("B"), "B") {
                buildFails = true
                keyChange()
                play("B")
                settle()
                assertEquals(emptyList<String>(), lookups)
                buildFails = false
                keyChange()
                settle()
            },
            Case(
                "a new length of a written track keeps its run and its insight: no lookup, no call again",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                play("A", seconds = 106)
                settle()
                tap()
                settle()
                // YouTube Music sends the playing track again with a new length: the same track.
                play("A", seconds = 279)
                settle()
                assertEquals(false, coordinator.state.run?.offered)
            },
            Case(
                "a new length while the track's insight is being written keeps the call and its run",
                mapOf("A" to 1),
                listOf("A"),
            ) {
                streamMs = 10_000
                play("A", seconds = 106)
                settle()
                tap()
                advance(2_000)
                play("A", seconds = 279)
                settle()
                assertEquals(listOf("A"), lookups)
                advance(30_000)
                assertEquals(emptyList<String>(), cut)
            },
            Case(
                "a stream's next song under one media id is a new track: written, the call before cut off",
                mapOf("Zero" to 1, "Song one" to 1, "Song two" to 1),
                listOf("Zero", "Song one", "Song two"),
            ) {
                acted()
                streamMs = 10_000
                report(nowPlaying("Song one", mediaId = "stream"))
                settle()
                assertEquals(1, calls["Song one"])
                report(nowPlaying("Song two", mediaId = "stream"))
                settle()
                assertEquals(listOf("Song one"), cut)
                advance(30_000)
            },
            Case(
                "a title first, with nothing found, then its artists: written without a tap",
                mapOf("Zero" to 1, "A" to 1),
                listOf("Zero", "A", "A"),
            ) {
                acted()
                report(nowPlaying("A", artists = ""))
                settle()
                assertEquals(mapOf("Zero" to 1), calls)
                report(nowPlaying("A"))
                settle()
            },
            Case(
                "a new length of a track offered Write, not tapped, is a new run, undecided",
                emptyMap(),
                listOf("A", "A"),
                "A",
            ) {
                play("A", seconds = 106)
                settle()
                play("A", seconds = 279)
                settle()
            },
            Case(
                "a run that ends while offering Write leaves nothing waiting for it",
                emptyMap(),
                listOf("A", "A"),
                "A",
            ) {
                play("A")
                settle()
                assertTrue(offersWriteFor("A"))
                send(CoordinatorEvent.RunEnded(coordinator.state.run!!.id))
                assertEquals(false, offersWriteFor("A"))
                tap()
                keyChange()
                settle()
            },
            Case(
                "On skip writes nothing for a change that ended on its own",
                mapOf("Zero" to 1),
                listOf("Zero", "B"),
                "B",
            ) {
                acted()
                writesOn = mode(WriteAutomatically.WHEN_I_SKIP)
                play("B")
                settle()
            },
        )

    @Test
    fun eachSequenceGrantsOnlyWhatTheRulesAllow() = checkAll(cases)

    @Test
    fun aFailedCallAddsNoCardAndAnOldRunsCardIsIgnored() {
        val world = started()
        world.play("A")
        world.settle()
        world.tap()
        world.settle()
        val first =
            world.coordinator.state.run!!
                .id
        assertEquals("A #1", world.cardText())

        // A rewrite whose call fails: granted, then its end, with no card.
        world.send(CoordinatorEvent.RebuildRequested)
        world.advance(0)
        world.advance(DEBOUNCE_MS + 5_000)
        val replay =
            world.coordinator.state.run!!
                .id
        world.send(CoordinatorEvent.WriteTapped(checkNotNull(world.coordinator.state.writeKey)))
        world.send(CoordinatorEvent.StepEnded(replay, RunStep.MODEL_CALL))
        assertEquals("A #1", world.cardText())

        // A card reported by the run before the rebuild: ignored.
        world.send(CoordinatorEvent.CardWritten(first, world.card("A, from an old run")))
        assertEquals("A #1", world.cardText())
    }

    @Test
    fun aTapForAnotherRunsKeyWritesNothing() {
        val world = started()
        world.play("A")
        world.settle()
        val offered = checkNotNull(world.coordinator.state.writeKey)
        assertEquals(world.key("A"), offered)

        // A screen that showed another track (or another key of this one) taps: nothing is granted.
        world.tap(world.key("B"))
        world.tap(world.key("A", seconds = 200))
        world.settle()
        assertEquals(emptyMap<String, Int>(), world.calls)
        assertEquals(offered, world.coordinator.state.writeKey)
        assertEquals("a tap rejected is no action", 0, world.actions)
        assertEquals(false, world.coordinator.state.acted)

        // The tap for the run offered writes it.
        world.tap(offered)
        world.settle()
        assertEquals(mapOf("A" to 1), world.calls)
        assertEquals(1, world.actions)
    }

    @Test
    fun requestsAreNotAllowedBeforeTheFirstRunIsAcknowledged() {
        val coordinator = PipelineCoordinator()
        coordinator.on(CoordinatorEvent.ActivityStarted)
        coordinator.on(CoordinatorEvent.BackgroundChanged(true))
        assertEquals(false, coordinator.state.allowed)
        coordinator.on(CoordinatorEvent.Acknowledged)
        assertEquals(true, coordinator.state.allowed)
    }

    @Test
    fun nothingIsBuiltOrStartedBeforeTheFirstRunIsAcknowledged() {
        val world = World()
        val effects =
            listOf(
                CoordinatorEvent.ActivityStarted,
                CoordinatorEvent.RebuildRequested,
                CoordinatorEvent.Reported(world.key("A"), world.nowPlaying("A")),
                CoordinatorEvent.Settled(1),
                CoordinatorEvent.WriteTapped(world.key("A")),
            ).flatMap(world.coordinator::on)
        assertEquals(emptyList<CoordinatorEffect>(), effects)
    }
}
