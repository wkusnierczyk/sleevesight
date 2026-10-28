package app.sleevesight.app.speech

import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** A text-to-speech engine that records what it is asked, and ends a reading when told ([finish]). */
internal class FakeSpeech(
    var opens: Boolean = true,
    var voices: Set<String> = setOf("en"),
) : Speech {
    val spoken = mutableListOf<List<String>>()
    var locale: Locale? = null
    var stops = 0
    var closes = 0
    var open = false
    private var done: (() -> Unit)? = null
    private var failed: (() -> Unit)? = null

    override fun open(ready: (Boolean) -> Unit) {
        open = opens
        ready(opens)
    }

    override fun speaks(locale: Locale): Boolean = locale.language in voices

    override fun speak(
        parts: List<String>,
        locale: Locale,
        done: () -> Unit,
        failed: () -> Unit,
    ) {
        spoken += parts
        this.locale = locale
        this.done = done
        this.failed = failed
    }

    override fun stop() {
        stops++
        done = null
        failed = null
    }

    override fun close() {
        stop()
        closes++
        open = false
    }

    /** The reading under way ends with its last word. */
    fun finish() = done?.invoke()

    /** The engine fails partway. */
    fun fail() = failed?.invoke()
}

/** The audio focus, recorded: taken how, released, and lost from the reading when told ([lose]). */
internal class FakeFocus(
    var grants: Boolean = true,
) : MusicFocus {
    val taken = mutableListOf<ReadAloudMusic>()
    var held = false
    var releases = 0
    private var lost: (() -> Unit)? = null

    override fun take(
        how: ReadAloudMusic,
        lost: () -> Unit,
    ): Boolean {
        if (!grants) return false
        taken += how
        held = true
        this.lost = lost
        return true
    }

    override fun release() {
        if (held) releases++
        held = false
    }

    /** Another app takes the focus (a call). */
    fun lose() = lost?.invoke()
}

/**
 * *Read aloud* (#325): a reading takes the audio focus as *While reading aloud* says and gives it
 * back at its end; it stops at once on Stop, on a track change, when what it reads is replaced, when
 * another app takes the sound, and when the app leaves the screen (not for a rotation); what cannot
 * be read is told, never read past; and *Read new insights aloud* reads only an insight written
 * while the app is on screen, never with a screen reader exploring, never one from the cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudTest {
    private val speech = FakeSpeech()
    private val focus = FakeFocus()
    private val settings = MutableStateFlow<Settings?>(Settings())
    private val told = mutableListOf<Pair<ReadAloudProblem, Locale>>()
    private var exploring = false
    private val words = SpokenWords("Unverified:") { title, artists -> "$title, by $artists." }
    private val readAloud =
        ReadAloud(
            speech,
            focus,
            settings,
            words,
            { exploring },
            { problem, locale -> told += problem to locale },
            CoroutineScope(Dispatchers.Unconfined),
        ) { Locale.UK }

    private val insight = written("Sundown was recorded in 1973 [MB1]. It was a hit in Canada.")
    private val shown = track(insight = insight)

    @Test
    fun aTapReadsTheInsightWithTheMusicLoweredAndGivesTheMusicBackAtItsEnd() {
        readAloud.activityStarted()
        readAloud.readInsight(shown, insight)

        assertEquals(
            listOf(listOf("Sundown was recorded in 1973.", "Unverified: It was a hit in Canada.")),
            speech.spoken,
        )
        assertEquals(Locale.UK, speech.locale)
        assertEquals(listOf(ReadAloudMusic.LOWER), focus.taken)
        assertEquals(Reading.INSIGHT, readAloud.reading.value?.id)

        speech.finish()
        assertNull(readAloud.reading.value)
        assertFalse(focus.held)
    }

    @Test
    fun pauseTheMusicTakesTheFocusToPause() {
        settings.value = Settings(readAloudMusic = ReadAloudMusic.PAUSE)
        readAloud.readInsight(shown, insight)
        assertEquals(listOf(ReadAloudMusic.PAUSE), focus.taken)
    }

    @Test
    fun withTheTrackLineFirstTheTitleAndArtistsComeFirst() {
        settings.value = Settings(readTrackLineFirst = true)
        readAloud.readInsight(shown, insight)
        assertEquals("Sundown, by Gordon Lightfoot.", speech.spoken.single().first())
    }

    @Test
    fun aGenresReasonIsReadAfterItsQuestion() {
        val reason = written("It is folk rock [MB1].")
        readAloud.readGenreReason(
            shown,
            "folk rock",
            "Why is this folk rock?",
            reason,
            flowOf(GenreReason.Shown(reason)),
        )
        assertEquals(listOf("Why is this folk rock?", "It is folk rock."), speech.spoken.single())
        assertEquals(Reading.genreReason("folk rock"), readAloud.reading.value?.id)
    }

    @Test
    fun stopEndsTheReadingAtOnceAndGivesTheMusicBack() {
        readAloud.readInsight(shown, insight)
        readAloud.stop()
        assertEquals(1, speech.stops)
        assertNull(readAloud.reading.value)
        assertFalse(focus.held)
        // The end it would have had changes nothing.
        speech.finish()
        assertNull(readAloud.reading.value)
    }

    @Test
    fun aReadingThatReplacesAnotherKeepsTheMusicDownBetweenThem() {
        readAloud.readInsight(shown, insight)
        val reason = written("It is folk rock [MB1].")
        readAloud.readGenreReason(
            shown,
            "folk rock",
            "Why is this folk rock?",
            reason,
            flowOf(GenreReason.Shown(reason)),
        )
        assertEquals("the focus taken once", listOf(ReadAloudMusic.LOWER), focus.taken)
        assertEquals("never given back between them", 0, focus.releases)
        assertTrue(focus.held)
        speech.finish()
        assertEquals(1, focus.releases)
        assertFalse(focus.held)
    }

    @Test
    fun aGenresReasonReplacedOrWithdrawnWhateverScreenShowsStopsItsReadingAndTheSameAgainDoesNot() {
        val reason = written("It is folk rock [MB1].")
        // The notes' reason, followed while it is read: no panel is composed here, as after
        // leaving Home for the settings.
        val reasons = MutableStateFlow<GenreReason>(GenreReason.Shown(reason))
        readAloud.readGenreReason(shown, "folk rock", "Why is this folk rock?", reason, reasons)
        reasons.value = GenreReason.Shown(reason.copy())
        assertEquals("the same reason again reads on", Reading.genreReason("folk rock"), readAloud.reading.value?.id)
        // Offered again (a settings change, *Clear history*): withdrawn.
        reasons.value = GenreReason.Offered
        assertNull(readAloud.reading.value)
        assertFalse(focus.held)

        reasons.value = GenreReason.Shown(reason)
        readAloud.readGenreReason(shown, "folk rock", "Why is this folk rock?", reason, reasons)
        reasons.value = GenreReason.Shown(written("It is folk rock, rewritten [MB1]."))
        assertNull("replaced: stopped", readAloud.reading.value)

        // Once it has ended, the reason is no longer followed: an insight read next reads on.
        readAloud.readInsight(shown, insight)
        reasons.value = GenreReason.None
        assertEquals(Reading.INSIGHT, readAloud.reading.value?.id)
    }

    @Test
    fun anotherAppTakingTheSoundStopsTheReading() {
        readAloud.readInsight(shown, insight)
        focus.lose()
        assertNull(readAloud.reading.value)
        assertEquals(1, speech.stops)
    }

    @Test
    fun aPhoneWithNoEngineIsToldAndNothingIsSpokenOrTaken() {
        speech.opens = false
        readAloud.readInsight(shown, insight)
        assertEquals(listOf(ReadAloudProblem.NO_ENGINE to Locale.UK), told)
        assertTrue(speech.spoken.isEmpty())
        assertTrue(focus.taken.isEmpty())
        assertNull(readAloud.reading.value)
    }

    @Test
    fun anEngineWithNoVoiceForTheLanguageIsToldNotReadWithAWrongVoice() {
        speech.voices = setOf("de")
        readAloud.readInsight(shown, insight)
        assertEquals(listOf(ReadAloudProblem.NO_VOICE to Locale.UK), told)
        assertTrue(speech.spoken.isEmpty())
        assertTrue(focus.taken.isEmpty())
    }

    @Test
    fun theFocusRefusedIsToldAndNothingIsSpoken() {
        focus.grants = false
        readAloud.readInsight(shown, insight)
        assertEquals(listOf(ReadAloudProblem.BUSY to Locale.UK), told)
        assertTrue(speech.spoken.isEmpty())
        assertNull(readAloud.reading.value)
    }

    @Test
    fun theEngineFailingIsToldAndTheMusicGivenBack() {
        readAloud.readInsight(shown, insight)
        speech.fail()
        assertEquals(listOf(ReadAloudProblem.FAILED to Locale.UK), told)
        assertNull(readAloud.reading.value)
        assertFalse(focus.held)
    }

    @Test
    fun leavingTheScreenStopsTheReadingAndReleasesTheEngineButARotationDoesNot() {
        readAloud.activityStarted()
        readAloud.readInsight(shown, insight)
        // A rotation: the activity stops to start again.
        readAloud.activityStopped(changingConfigurations = true)
        readAloud.activityStarted()
        assertEquals(Reading.INSIGHT, readAloud.reading.value?.id)
        assertEquals(0, speech.closes)

        readAloud.activityStopped(changingConfigurations = false)
        assertNull(readAloud.reading.value)
        assertFalse(focus.held)
        assertEquals(1, speech.closes)
    }

    @Test
    fun aTrackChangeStopsTheReadingAndTheSameTrackPlayingOnDoesNot() =
        runTest(UnconfinedTestDispatcher()) {
            val playing = MutableStateFlow<NowPlaying?>(sundown)
            val view = MutableStateFlow(PipelineView(shown))
            backgroundScope.launch { readAloud.follow(playing, view) }
            readAloud.readInsight(shown, insight)

            // Its album reported later: the same track, playing on.
            playing.value = sundown.copy(album = "Sundown (Deluxe)")
            assertEquals(Reading.INSIGHT, readAloud.reading.value?.id)

            playing.value = carefree
            assertNull(readAloud.reading.value)
            assertEquals(1, speech.stops)
            assertFalse(focus.held)
        }

    @Test
    fun thePlayersSessionEndingStopsTheReading() =
        runTest(UnconfinedTestDispatcher()) {
            val playing = MutableStateFlow<NowPlaying?>(sundown)
            backgroundScope.launch { readAloud.follow(playing, MutableStateFlow(PipelineView(shown))) }
            readAloud.readInsight(shown, insight)
            playing.value = null
            assertNull(readAloud.reading.value)
        }

    @Test
    fun theInsightReplacedOnTheCardStopsItsReading() =
        runTest(UnconfinedTestDispatcher()) {
            val view = MutableStateFlow(PipelineView(shown))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            readAloud.readInsight(shown, insight)
            // The same insight again, with a source landing: it reads on.
            view.value = PipelineView(shown, aiReady = true)
            assertEquals(Reading.INSIGHT, readAloud.reading.value?.id)

            view.value = PipelineView(track(insight = written("It was rewritten [MB1].", cached = true)))
            assertNull(readAloud.reading.value)
        }

    // The pipeline writing an insight for Sundown: under way, then written as [landed].
    private fun MutableStateFlow<PipelineView>.writes(landed: InsightState.Written) {
        value = PipelineView(track(insight = InsightState.Writing("Sundown was")))
        value = PipelineView(track(insight = landed))
    }

    @Test
    fun newInsightsAreNotReadByThemselvesByDefault() =
        runTest(UnconfinedTestDispatcher()) {
            readAloud.activityStarted()
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            view.writes(insight)
            assertTrue(speech.spoken.isEmpty())
        }

    @Test
    fun withReadNewInsightsAloudAnInsightWrittenOnScreenIsReadAsItLands() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            readAloud.activityStarted()
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            view.writes(insight)
            assertEquals(
                listOf(listOf("Sundown was recorded in 1973.", "Unverified: It was a hit in Canada.")),
                speech.spoken,
            )
            // The same insight shown again is not new.
            view.value = PipelineView(track(insight = insight), aiReady = true)
            assertEquals(1, speech.spoken.size)
        }

    @Test
    fun anInsightFromTheCacheIsNotNew() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            readAloud.activityStarted()
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            view.value = PipelineView(track(insight = written("Sundown was recorded in 1973 [MB1].", cached = true)))
            assertTrue(speech.spoken.isEmpty())
        }

    @Test
    fun anInsightWrittenWhileTheAppWasAwayIsNotReadOnItsReturn() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            // Not on screen (*Look up in the background* on): written, not read.
            view.writes(insight)
            readAloud.activityStarted()
            view.value = PipelineView(track(insight = insight), aiReady = true)
            assertTrue(speech.spoken.isEmpty())
        }

    @Test
    fun aScreenReaderExploringHoldsBackReadingByItselfButNotTheButton() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            exploring = true
            readAloud.activityStarted()
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(sundown), view) }
            view.writes(insight)
            assertTrue(speech.spoken.isEmpty())

            readAloud.readInsight(shown, insight)
            assertEquals(1, speech.spoken.size)
        }

    @Test
    fun theInsightShownWhenFollowingBeginsIsNotNew() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            readAloud.activityStarted()
            backgroundScope.launch {
                readAloud.follow(
                    MutableStateFlow(sundown),
                    MutableStateFlow(PipelineView(shown)),
                )
            }
            assertTrue(speech.spoken.isEmpty())
        }

    @Test
    fun anInsightForATrackNoLongerPlayingIsNotRead() =
        runTest(UnconfinedTestDispatcher()) {
            settings.value = Settings(readNewInsightsAloud = true)
            readAloud.activityStarted()
            val view = MutableStateFlow(PipelineView(track()))
            backgroundScope.launch { readAloud.follow(MutableStateFlow(carefree), view) }
            view.writes(insight)
            assertTrue(speech.spoken.isEmpty())
        }

    @Test
    fun theInsightsLanguageIsEnglishInThePhonesOwnVarietyOfIt() {
        assertEquals(Locale.UK, insightLocale(Locale.UK))
        assertEquals(Locale.ENGLISH, insightLocale(Locale.GERMANY))
    }
}
