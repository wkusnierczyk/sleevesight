package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.ChangeCause.NATURAL_END
import app.sleevesight.core.pipeline.ChangeCause.SKIP_IN_PLAYER
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * D5's rules for telling a skip in the player from a natural end (`design/design.md`, *Track
 * changes*), the crossfade learned from natural ends, and the detector that applies them, pure.
 * The times left and state counts are those measured in Phase 0 where there are some.
 */
class SkipDetectionTest {
    private class Row(
        val name: String,
        val features: ChangeFeatures,
        val crossfade: Crossfade,
        val learned: Duration?,
        val cause: ChangeCause,
    )

    @Suppress("LongParameterList") // One per input of the rules, most with a default.
    private fun row(
        name: String,
        left: Duration?,
        states: Int = 0,
        crossfade: Crossfade = Crossfade.Autodetect,
        learned: Duration? = null,
        player: String = SPOTIFY,
        cause: ChangeCause,
    ) = Row(name, ChangeFeatures(player, left, states), crossfade, learned, cause)

    private val rows =
        listOf(
            // Autodetect, Spotify.
            row("a natural end without crossfade, its cluster there", (-60).milliseconds, 6, cause = NATURAL_END),
            row("a natural end without crossfade, no cluster (a late start)", (-60).milliseconds, cause = NATURAL_END),
            row("a change past the end, no cluster", (-3).seconds, cause = NATURAL_END),
            row("within the margin of 0", 500.milliseconds, cause = NATURAL_END),
            row("just past the margin of 0, no crossfade learned", 501.milliseconds, cause = SKIP_IN_PLAYER),
            row("a natural end at a 12 s crossfade, its cluster there", 11_960.milliseconds, 6, cause = NATURAL_END),
            row(
                "a natural end at a 12 s crossfade, no cluster, none learned: the accepted exception",
                11_960.milliseconds,
                cause = SKIP_IN_PLAYER,
            ),
            row(
                "a natural end at the learned crossfade, no cluster",
                11_960.milliseconds,
                learned = 12.seconds,
                cause = NATURAL_END,
            ),
            row(
                "within the margin of the learned crossfade",
                12_500.milliseconds,
                learned = 12.seconds,
                cause = NATURAL_END,
            ),
            row(
                "past the margin of the learned crossfade",
                12_600.milliseconds,
                learned = 12.seconds,
                cause = SKIP_IN_PLAYER,
            ),
            row("four states are no cluster", 11_960.milliseconds, 4, cause = SKIP_IN_PLAYER),
            row("the cluster at the edge of the window", 15.seconds, 5, cause = NATURAL_END),
            row("a cluster past the window (around a seek)", 15_001.milliseconds, 6, cause = SKIP_IN_PLAYER),
            row("a skip from the app, earphones or the notification", 20.seconds, cause = SKIP_IN_PLAYER),
            row("a skip just after pressing play", 175.seconds, 4, cause = SKIP_IN_PLAYER),
            row("no time left known (a switch, no length)", null, cause = NATURAL_END),
            // Autodetect, another player: timing alone.
            row(
                "YouTube Music, a late skip reads as a natural end",
                7_200.milliseconds,
                player = YOUTUBE,
                cause = NATURAL_END,
            ),
            row("YouTube Music, at the window", 15.seconds, player = YOUTUBE, cause = NATURAL_END),
            row("YouTube Music, past the window", 15_001.milliseconds, player = YOUTUBE, cause = SKIP_IN_PLAYER),
            row("YouTube Music, states are no cluster", 20.seconds, 6, player = YOUTUBE, cause = SKIP_IN_PLAYER),
            row("YouTube Music, a mid-track skip", 131.seconds, player = YOUTUBE, cause = SKIP_IN_PLAYER),
            // A fixed crossfade.
            row(
                "fixed 0, within the margin",
                500.milliseconds,
                crossfade = FIXED_0,
                player = YOUTUBE,
                cause = NATURAL_END,
            ),
            row(
                "fixed 0, past the margin",
                501.milliseconds,
                crossfade = FIXED_0,
                player = YOUTUBE,
                cause = SKIP_IN_PLAYER,
            ),
            row("fixed 0, past the end", (-2).seconds, crossfade = FIXED_0, player = YOUTUBE, cause = NATURAL_END),
            row(
                "fixed 0 set below Spotify's 12 s: its cluster still reads natural",
                11_960.milliseconds,
                6,
                crossfade = FIXED_0,
                cause = NATURAL_END,
            ),
            row(
                "fixed 0, Spotify, no cluster at 12 s",
                11_960.milliseconds,
                crossfade = FIXED_0,
                cause = SKIP_IN_PLAYER,
            ),
            row(
                "fixed 0 ignores the learned crossfade",
                11_960.milliseconds,
                crossfade = FIXED_0,
                learned = 12.seconds,
                cause = SKIP_IN_PLAYER,
            ),
            row(
                "fixed 0, Spotify, a cluster past the window",
                16.seconds,
                6,
                crossfade = FIXED_0,
                cause = SKIP_IN_PLAYER,
            ),
            row("fixed 12, inside it", 5.seconds, crossfade = FIXED_12, player = YOUTUBE, cause = NATURAL_END),
            row(
                "fixed 12, at its margin",
                12_500.milliseconds,
                crossfade = FIXED_12,
                player = YOUTUBE,
                cause = NATURAL_END,
            ),
            row(
                "fixed 12, past its margin",
                12_501.milliseconds,
                crossfade = FIXED_12,
                player = YOUTUBE,
                cause = SKIP_IN_PLAYER,
            ),
            row(
                "fixed 15, at its margin, past the window",
                15_500.milliseconds,
                crossfade = FIXED_15,
                cause = NATURAL_END,
            ),
            row("fixed, no time left known", null, crossfade = FIXED_0, cause = NATURAL_END),
        )

    @Test
    fun eachChangeIsJudgedByTheRules() {
        for (row in rows) {
            assertEquals(row.name, row.cause, SkipDetection.cause(row.features, row.crossfade, row.learned))
        }
    }

    @Test
    fun onlyAClusteredEndOfAMeasuredPlayerTeaches() {
        assertEquals(12.seconds, SkipDetection.clusteredEnd(ChangeFeatures(SPOTIFY, 12.seconds, 5)))
        assertNull("no cluster", SkipDetection.clusteredEnd(ChangeFeatures(SPOTIFY, 12.seconds, 4)))
        assertNull("past the window", SkipDetection.clusteredEnd(ChangeFeatures(SPOTIFY, 16.seconds, 6)))
        assertNull("no time left", SkipDetection.clusteredEnd(ChangeFeatures(SPOTIFY, null, 6)))
        assertNull("another player", SkipDetection.clusteredEnd(ChangeFeatures(YOUTUBE, 12.seconds, 6)))
    }

    @Test
    fun theFeaturesCountThePlayingStatesOfTheLateWindow() {
        val change =
            change(
                left = 1.seconds,
                states =
                    listOf(
                        report(1_501, PlaybackStatus.PLAYING),
                        report(1_500, PlaybackStatus.PLAYING),
                        report(800, PlaybackStatus.PAUSED),
                        report(700, PlaybackStatus.PLAYING),
                        report(0, PlaybackStatus.PLAYING),
                    ),
            )
        assertEquals(ChangeFeatures(SPOTIFY, 1.seconds, 3), ChangeFeatures.of(change))

        val record =
            record(
                left = 1.seconds,
                states =
                    listOf(
                        stateRecord(1_501, "PLAYING"),
                        stateRecord(1_500, "PLAYING"),
                        stateRecord(800, "PAUSED"),
                        stateRecord(700, "PLAYING"),
                        stateRecord(0, "PLAYING"),
                    ),
            )
        assertEquals(ChangeFeatures(SPOTIFY, 1.seconds, 3), ChangeFeatures.of(record))
    }

    @Test
    fun twoNaturalEndsInARowThatAgreeAreTheCrossfade() {
        val one = CrossfadeLearning().learn(12.seconds)
        assertEquals(CrossfadeLearning(candidate = 12.seconds), one)
        assertEquals(CrossfadeLearning(value = 12_050.milliseconds), one.learn(12_100.milliseconds))
        assertEquals("0.11 s apart", CrossfadeLearning(candidate = 12_110.milliseconds), one.learn(12_110.milliseconds))
    }

    @Test
    fun twoLaterOnesInARowThatAgreeReplaceIt() {
        val learnt = CrossfadeLearning().learn(12.seconds).learn(12.seconds)
        val waiting = learnt.learn(5.seconds)
        assertEquals(CrossfadeLearning(value = 12.seconds, candidate = 5.seconds), waiting)
        assertEquals(CrossfadeLearning(value = 5_010.milliseconds), waiting.learn(5_020.milliseconds))
        // One that agrees with the crossfade in between: the two are not in a row.
        assertEquals(
            CrossfadeLearning(value = 12.seconds, candidate = 5.seconds),
            waiting.learn(12_050.milliseconds).learn(5.seconds),
        )
        // One that agrees with neither replaces the one waiting.
        assertEquals(CrossfadeLearning(value = 12.seconds, candidate = 8.seconds), waiting.learn(8.seconds))
    }

    @Test
    fun theDetectorJudgesWithWhatItHadLearnedAndLearnsAfter() {
        val detector = SkipDetector { Crossfade.Autodetect }
        val first = detector.classify(change(left = 11_980.milliseconds, playing = 6))
        assertEquals(NATURAL_END, first.cause)
        assertNull(first.learnedCrossfade)
        val second = detector.classify(change(left = 11_960.milliseconds, playing = 6))
        assertNull("learned only after it judged", second.learnedCrossfade)
        assertEquals(mapOf(SPOTIFY to 11_970.milliseconds), detector.learned.value)

        // A natural end the cluster is missing from, as after a restart, is now recognised.
        val late = detector.classify(change(left = 11_950.milliseconds))
        assertEquals(NATURAL_END, late.cause)
        assertEquals(11_970.milliseconds, late.learnedCrossfade)
    }

    @Test
    fun onlyClusteredEndsTeachTheDetector() {
        val detector = SkipDetector { Crossfade.Autodetect }
        repeat(2) { detector.classify(change(left = (-50).milliseconds)) }
        repeat(2) { detector.classify(change(left = 30.seconds)) }
        repeat(2) { detector.classify(change(left = 12.seconds, playing = 6, player = YOUTUBE)) }
        assertEquals(emptyMap<String, Duration>(), detector.learned.value)
    }

    @Test
    fun theDetectorReadsTheCrossfadeAtEachChange() {
        var crossfade: Crossfade = Crossfade.Autodetect
        val detector = SkipDetector { crossfade }
        assertEquals(SKIP_IN_PLAYER, detector.classify(change(left = 8.seconds)).cause)
        crossfade = Crossfade.Fixed(10.seconds)
        assertEquals(NATURAL_END, detector.classify(change(left = 8.seconds)).cause)
    }

    @Test
    fun restoringRelearnsFromTheRecordedChangesOldestFirst() {
        val detector = SkipDetector { Crossfade.Autodetect }
        assertFalse(detector.restored.value)
        // As the store pages them, newest first: 12 s, 12 s, then 5 s waiting.
        detector.restore(
            listOf(record(5.seconds, clustered()), record(12.seconds, clustered()), record(12.seconds, clustered())),
        )
        assertTrue(detector.restored.value)
        assertEquals(mapOf(SPOTIFY to 12.seconds), detector.learned.value)
        // The 5 s waiting agrees with the next one: the order was kept.
        detector.classify(change(left = 5_020.milliseconds, playing = 6))
        assertEquals(mapOf(SPOTIFY to 5_010.milliseconds), detector.learned.value)
    }

    @Test
    fun aResetForgetsWhatWasLearned() {
        val detector = SkipDetector { Crossfade.Autodetect }
        repeat(2) { detector.classify(change(left = 12.seconds, playing = 6)) }
        detector.reset()
        assertEquals(emptyMap<String, Duration>(), detector.learned.value)
        assertNull(detector.classify(change(left = 12.seconds)).learnedCrossfade)
    }

    @Test
    fun aCauseIsCarriedOnlyAcrossReportsThatPlayOnAndAnyBreakDropsIt() {
        val b = NowPlaying(title = "B", artists = "Artist", player = SPOTIFY, mediaId = "b")
        val filledIn = b.copy(album = "Album", duration = 200.seconds)
        val nextSong = b.copy(title = "C")
        val skippedTo = CarriedCause(b, SKIP_IN_PLAYER)
        // The cause carried before, the track before, the track now, the change judged into it.
        val rows =
            listOf(
                Triple("a change judged into it", Given(null, A, b, SKIP_IN_PLAYER), skippedTo),
                Triple("a natural end judged", Given(skippedTo, A, b, NATURAL_END), CarriedCause(b, NATURAL_END)),
                Triple("the same track again", Given(skippedTo, b, b, null), skippedTo),
                Triple(
                    "filled in: plays on",
                    Given(skippedTo, b, filledIn, null),
                    CarriedCause(filledIn, SKIP_IN_PLAYER),
                ),
                Triple("a new title under one media id", Given(skippedTo, b, nextSong, null), null),
                Triple("no track", Given(skippedTo, b, null, null), null),
                Triple("a track after none", Given(skippedTo, null, b, null), null),
                Triple("nothing carried", Given(null, b, filledIn, null), null),
            )
        for ((name, given, expected) in rows) {
            assertEquals(name, expected, CarriedCause.next(given.carried, given.previous, given.track, given.judged))
        }
    }

    private data class Given(
        val carried: CarriedCause?,
        val previous: NowPlaying?,
        val track: NowPlaying?,
        val judged: ChangeCause?,
    )

    @Test
    fun aRestoreAClearOvertookLearnsNothing() {
        val records = listOf(record(12.seconds, clustered()), record(12.seconds, clustered()))
        val cleared = SkipDetector { Crossfade.Autodetect }
        runBlocking {
            cleared.restoreFrom {
                // *Clear history* between the read and the restore.
                cleared.reset()
                records
            }
        }
        assertTrue(cleared.restored.value)
        assertEquals(emptyMap<String, Duration>(), cleared.learned.value)

        val kept = SkipDetector { Crossfade.Autodetect }
        runBlocking { kept.restoreFrom { records } }
        assertEquals(mapOf(SPOTIFY to 12.seconds), kept.learned.value)
    }

    @Test
    fun theLatestActionBeforeAChangeIsTheOneTimed() {
        var now = 0L
        val actions = UserActions { now }
        assertNull(actions.lastAt(1_000))
        now = 1_000
        actions.acted()
        now = 3_000
        actions.acted()
        assertEquals(1_000L, actions.lastAt(2_999))
        assertEquals(3_000L, actions.lastAt(3_000))
        assertNull(actions.lastAt(999))
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.apps.youtube.music"
        const val AT = 100_000L
        val A = NowPlaying(title = "A", artists = "Artist", player = SPOTIFY, mediaId = "a")
        val LENGTH: Duration = 200.seconds
        val FIXED_0 = Crossfade.Fixed(Duration.ZERO)
        val FIXED_12 = Crossfade.Fixed(12.seconds)
        val FIXED_15 = Crossfade.Fixed(15.seconds)

        /**
         * A change at [AT] from a track of [LENGTH] with [left] to go, after [playing] `PLAYING`
         * states in the last second.
         */
        fun change(
            left: Duration,
            playing: Int = 0,
            player: String = SPOTIFY,
            states: List<StateReport> = List(playing) { report(100L * (it + 1), PlaybackStatus.PLAYING) },
        ): SessionChange {
            val before = PlaybackInfo(player, PlaybackStatus.PLAYING, LENGTH - left, updatedAt = AT, speed = 1f)
            return SessionChange(
                track = NowPlaying(title = "B", artists = "Artist", player = player),
                changedAt = AT,
                before = before,
                from = NowPlaying(title = "A", artists = "Artist", duration = LENGTH, player = player),
                states = states,
            )
        }

        fun report(
            msBefore: Long,
            status: PlaybackStatus,
        ) = StateReport(AT - msBefore, PlaybackInfo(SPOTIFY, status, Duration.ZERO, AT - msBefore, 1f))

        fun stateRecord(
            msBefore: Long,
            status: String,
        ) = PlaybackStateRecord(msBefore.milliseconds, status, null, null, 1f)

        fun clustered() = List(6) { stateRecord(100L * (it + 1), "PLAYING") }

        fun record(
            left: Duration?,
            states: List<PlaybackStateRecord>,
        ) = TrackChangeRecord(
            player = SPOTIFY,
            changedAt = Instant.parse("2026-10-17T08:00:00Z"),
            from = TrackRef("A", null),
            to = TrackRef("B", null),
            timeLeft = left,
            states = states,
            sinceLastAction = null,
            learnedCrossfade = null,
        )
    }
}
