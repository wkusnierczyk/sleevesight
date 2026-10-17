package app.sleevesight.app.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.RunStep
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.synthesis.GroundedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.time.Duration.Companion.seconds

/**
 * The coordinator's rules (#51; D5, D6), pure: each sequence from the reviews of PR #155 runs
 * through [PipelineCoordinator] in a small simulated world, which carries out its effects as the
 * host and the runs would (timers, builds, lookups that take a while, model calls that stream),
 * in virtual time, with no threads. Each case states the model calls per track, the tracks whose
 * lookups were granted, and the track Write is offered for at the end.
 */
class PipelineCoordinatorTest {
    /** The host, the pipelines and the runs, as the coordinator's effects set them going. */
    private class World {
        val coordinator = PipelineCoordinator()
        private val normalizer = Normalizer(JvmAccentFolder)
        private var now = 0L
        private var order = 0L

        private class Scheduled(
            val at: Long,
            val order: Long,
            val run: RunId?,
            val event: CoordinatorEvent,
        )

        private val queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.at }, { it.order }))
        private val titles = HashMap<RunId, String>()
        private val cancelled = HashSet<RunId>()
        private val calling = HashSet<RunId>()
        private val held = mutableListOf<CoordinatorEvent>()

        val calls = linkedMapOf<String, Int>()
        val lookups = mutableListOf<String>()

        /** The tracks whose model call was cancelled while under way: paid for, and lost. */
        val cut = mutableListOf<String>()

        var lookupMs = 0L
        var streamMs = 0L
        var writesWhenActed = true
        var holdKeys = false
        var buildFails = false

        fun key(
            title: String,
            seconds: Long? = null,
        ): RunKey = RunKey.of(normalizer, nowPlaying(title, seconds))

        fun key(nowPlaying: NowPlaying): RunKey = RunKey.of(normalizer, nowPlaying)

        fun nowPlaying(
            title: String,
            seconds: Long? = null,
            artists: String = "Artist",
            mediaId: String? = null,
        ) = NowPlaying(
            title = title,
            artists = artists,
            duration = seconds?.seconds,
            player = "com.spotify.music",
            mediaId = mediaId,
        )

        // A report with no artists finds nothing: the run decides without a call.
        private val withoutEvidence = HashSet<RunId>()

        /**
         * With [cache] on, the world keeps what the store would: the keys looked up, and the cards
         * written under a key and the AI service (or evidence) in use, [service]; a rebuild that
         * changes the card's key raises [service].
         */
        var cache = false
        var service = 0
        val cards = HashSet<Pair<RunKey, Int>>()
        val cardsShown = mutableListOf<String>()
        private val retrieved = HashSet<RunKey>()
        private val keys = HashMap<RunId, RunKey>()
        private val pendingCards = HashMap<RunId, Pair<RunKey, Int>>()

        fun send(event: CoordinatorEvent) = coordinator.on(event).forEach(::perform)

        private fun schedule(
            delayMs: Long,
            event: CoordinatorEvent,
            run: RunId? = null,
        ) {
            queue += Scheduled(now + delayMs, order++, run, event)
        }

        private fun perform(effect: CoordinatorEffect) {
            when (effect) {
                is CoordinatorEffect.StartTimer -> schedule(DEBOUNCE_MS, CoordinatorEvent.Settled(effect.report))
                is CoordinatorEffect.Build -> {
                    val finished = CoordinatorEvent.RebuildFinished(effect.build, built = !buildFails)
                    if (holdKeys) held += finished else schedule(0, finished)
                }
                is CoordinatorEffect.Swap -> Unit
                is CoordinatorEffect.StartRun -> {
                    titles[effect.run] = effect.nowPlaying.title
                    keys[effect.run] = key(effect.nowPlaying)
                    if (effect.nowPlaying.artists.isEmpty()) withoutEvidence += effect.run
                    // With the cache, a key looked up before needs no lookups.
                    if (cache && keys.getValue(effect.run) in retrieved) {
                        gathered(effect.run, delayMs = 0)
                    } else {
                        schedule(0, CoordinatorEvent.StepRequested(effect.run, RunStep.LOOKUPS), effect.run)
                    }
                }
                is CoordinatorEffect.CancelRun -> cancel(effect.run)
                is CoordinatorEffect.Grant -> granted(effect.run, effect.step)
                is CoordinatorEffect.Decide -> if (effect.write) granted(effect.run, RunStep.MODEL_CALL)
            }
        }

        private fun cancel(run: RunId) {
            cancelled += run
            // The run's job ends, and says so from its `finally`; a call cut off reports its end too.
            schedule(0, CoordinatorEvent.RunEnded(run))
            if (calling.remove(run)) {
                cut += titles.getValue(run)
                schedule(0, CoordinatorEvent.StepEnded(run, RunStep.MODEL_CALL))
            }
        }

        private fun granted(
            run: RunId,
            step: RunStep,
        ) {
            val title = titles.getValue(run)
            when (step) {
                RunStep.LOOKUPS -> {
                    lookups += title
                    if (cache) retrieved += keys.getValue(run)
                    schedule(lookupMs, CoordinatorEvent.StepEnded(run, step), run)
                    gathered(run, lookupMs)
                }
                RunStep.LABELS -> schedule(0, CoordinatorEvent.StepEnded(run, step), run)
                RunStep.MODEL_CALL -> {
                    calls[title] = (calls[title] ?: 0) + 1
                    calling += run
                    // The card is reported before the call's end; neither, for a call cut off.
                    schedule(streamMs, CoordinatorEvent.CardWritten(run, card("$title #${calls[title]}")), run)
                    schedule(streamMs, CoordinatorEvent.StepEnded(run, step), run)
                    // Kept once it ends normally; a call cut off is not (`deliver` drops its end).
                    if (cache) pendingCards[run] = keys.getValue(run) to service
                }
            }
        }

        // The evidence in: a card kept under this key and service is shown; else the question.
        private fun gathered(
            run: RunId,
            delayMs: Long,
        ) {
            val insight =
                when {
                    cache && (keys.getValue(run) to service) in cards -> {
                        cardsShown += titles.getValue(run)
                        CoordinatorEvent.CardShown(run, card("${titles.getValue(run)} kept"))
                    }
                    run in withoutEvidence -> CoordinatorEvent.DecidedWithoutCall(run)
                    else -> CoordinatorEvent.InsightAsked(run, writesWhenActed)
                }
            schedule(delayMs, insight, run)
            schedule(delayMs, CoordinatorEvent.StepRequested(run, RunStep.LABELS), run)
        }

        /** Lets [ms] pass, delivering what falls due; what a cancelled run would have sent is dropped. */
        fun advance(ms: Long) {
            val until = now + ms
            while (queue.peek()?.let { it.at <= until } == true) {
                val next = queue.poll()
                now = next.at
                deliver(next.run, next.event)
            }
            now = until
        }

        private fun deliver(
            run: RunId?,
            event: CoordinatorEvent,
        ) {
            if (run != null && run in cancelled) return
            if (run != null && event is CoordinatorEvent.StepEnded && event.step == RunStep.MODEL_CALL) {
                calling -= run
                pendingCards.remove(run)?.let { cards += it }
            }
            send(event)
        }

        fun releaseKeys() {
            holdKeys = false
            held.forEach { schedule(0, it) }
            held.clear()
        }

        fun offersWriteFor(title: String) = coordinator.state.offersWrite(key(title))

        fun card(text: String) = Card(InsightState.Written(GroundedText(text, emptyList()), cached = false), null)

        /** The text of the card the ledger holds. */
        fun cardText(): String? =
            coordinator.state.ledger
                ?.card
                ?.insight
                ?.grounded
                ?.text
    }

    // The steps of a sequence.
    private fun World.play(
        title: String,
        cause: ChangeCause = ChangeCause.NATURAL_END,
        seconds: Long? = null,
    ) = report(nowPlaying(title, seconds), cause)

    private fun World.report(
        playing: NowPlaying,
        cause: ChangeCause = ChangeCause.NATURAL_END,
    ) {
        send(CoordinatorEvent.Reported(key(playing), playing, cause))
        advance(0)
    }

    private fun World.settle() = advance(DEBOUNCE_MS + 5_000)

    private fun World.hide() = send(CoordinatorEvent.ActivityStopped(changingConfigurations = false))

    private fun World.show() {
        send(CoordinatorEvent.ActivityStarted)
        advance(0)
    }

    private fun World.tap() {
        send(CoordinatorEvent.WriteTapped)
        advance(0)
    }

    private fun World.keyChange() {
        send(CoordinatorEvent.RebuildRequested)
        advance(0)
    }

    private fun World.background(on: Boolean) {
        send(CoordinatorEvent.BackgroundChanged(on))
        advance(0)
    }

    /** A first action made: Zero played and written on a tap. */
    private fun World.acted() {
        play("Zero")
        settle()
        tap()
        settle()
    }

    private fun started(): World =
        World().apply {
            send(CoordinatorEvent.Acknowledged)
            send(CoordinatorEvent.ActivityStarted)
            send(CoordinatorEvent.BackgroundChanged(false))
            send(CoordinatorEvent.RebuildRequested)
            advance(0)
        }

    private class Case(
        val name: String,
        val calls: Map<String, Int>,
        val lookups: List<String>,
        val offered: String? = null,
        val script: World.() -> Unit,
    )

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
                "Write, hide, another track, show: nothing is written until a tap",
                mapOf("Zero" to 1, "B" to 1),
                listOf("Zero", "B"),
            ) {
                acted()
                hide()
                play("B")
                settle()
                show()
                settle()
                assertEquals(mapOf("Zero" to 1), calls)
                assertTrue(offersWriteFor("B"))
                tap()
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
                mapOf("Zero" to 1),
                listOf("Zero", "B"),
                "B",
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
                "an opening during a rebuild clears the first action",
                mapOf("Zero" to 1),
                listOf("Zero", "Zero", "B"),
                "B",
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
                mapOf("A" to 1),
                listOf("A", "B"),
                "B",
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
                cards += key("A", 106) to service
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
                "a rewrite's card replaces the one before in the ledger; a failed call adds none",
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
                // The card stays through another rebuild, and the AI service gone.
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
                "When I skip writes nothing for a change that ended on its own",
                mapOf("Zero" to 1),
                listOf("Zero", "B"),
                "B",
            ) {
                acted()
                writesWhenActed = false
                play("B")
                settle()
            },
        )

    @Test
    fun eachSequenceGrantsOnlyWhatTheRulesAllow() {
        for (case in cases) {
            val world = started()
            world.(case.script)()
            assertEquals(case.name, case.calls, world.calls)
            assertEquals(case.name, case.lookups, world.lookups)
            val run = world.coordinator.state.current
            val offered = run?.takeIf { world.coordinator.state.offersWrite(it.key) }?.nowPlaying?.title
            assertEquals(case.name, case.offered, offered)
        }
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
                CoordinatorEvent.WriteTapped,
            ).flatMap(world.coordinator::on)
        assertEquals(emptyList<CoordinatorEffect>(), effects)
    }

    private companion object {
        const val DEBOUNCE_MS = 650L
    }
}
