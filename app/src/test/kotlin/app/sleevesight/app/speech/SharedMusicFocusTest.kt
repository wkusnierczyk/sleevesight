package app.sleevesight.app.speech

import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * One hold on the music for the readings and *Voice*'s samples (#787), as the app wires them
 * (`AppGraph`): music on a remote speaker whose volume the phone cannot set (Spotify Connect,
 * #803) is paused once, kept paused when a sample replaces a sample, a sample a reading, or a
 * reading a sample, each engine starting late ([SlowSpeech]), and played again only when the last
 * one ends. The speaker reports its state late ([FakeRemoteMusic] keeps reporting itself paused
 * after a `play`), so a hold given back and taken again between the two would find nothing playing
 * and leave the music at full volume under the next.
 */
class SharedMusicFocusTest {
    // Reports its pause at once, and its play only later: until then it reads as paused.
    private val remote = FakeRemoteMusic().apply { answersAtOnce = true }
    private val audio = FakeFocus()
    private lateinit var voices: SystemReadAloudVoices
    private lateinit var readAloud: ReadAloud
    private val shared =
        SharedMusicFocus(ReadAloudMusicFocus(audio) { remote.takeIf { it.playing } }) {
            readAloud.reading.value != null || voices.sampling
        }
    private val readingSpeech = SlowSpeech()
    private val sampleSpeech = SlowSpeech()

    init {
        readAloud =
            ReadAloud(
                readingSpeech,
                shared.party(),
                MutableStateFlow<Settings?>(Settings()),
                SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
                { false },
                { _, _ -> },
                CoroutineScope(Dispatchers.Unconfined),
                before = { voices.stopSample() },
            ) { Locale.UK }
        voices =
            SystemReadAloudVoices(
                speechOf = { sampleSpeech },
                focus = shared.party(),
                online = flowOf(true),
                before = { readAloud.stop() },
                locale = { Locale.UK },
            )
    }

    private val insight = written("Sundown was recorded in 1973 [MB1].")
    private val shown = track(insight = insight)

    private fun sample(id: String) = voices.sample(id, "This is how your insights will sound in this voice.") {}

    private fun read() = readAloud.readInsight(shown, insight)

    private fun paused() {
        assertEquals(listOf("pause"), remote.asked)
        assertTrue(audio.held)
    }

    private fun givenBack() {
        assertEquals(listOf("pause", "play"), remote.asked)
        assertFalse(audio.held)
    }

    @Test
    fun aSampleReplacingASampleKeepsTheMusicPausedUntilItEnds() {
        sample("a")
        sampleSpeech.start()
        paused()
        sample("b")
        paused()
        sampleSpeech.start()
        paused()
        sampleSpeech.inner.finish()
        givenBack()
    }

    @Test
    fun aSampleReplacingAReadingKeepsTheMusicPausedUntilItEnds() {
        read()
        readingSpeech.start()
        paused()
        sample("a")
        paused()
        sampleSpeech.start()
        paused()
        sampleSpeech.inner.finish()
        givenBack()
    }

    @Test
    fun aReadingReplacingASampleKeepsTheMusicPausedUntilItEnds() {
        sample("a")
        sampleSpeech.start()
        paused()
        read()
        paused()
        readingSpeech.start()
        paused()
        readingSpeech.inner.finish()
        givenBack()
    }

    @Test
    fun aReplacementThatCannotStartGivesTheMusicBack() {
        sample("a")
        sampleSpeech.start()
        paused()
        sampleSpeech.inner.opens = false
        sample("b")
        sampleSpeech.start()
        givenBack()
    }

    @Test
    fun theFocusLostDuringAHandOverIsAskedForAnewByTheNextTake() {
        // The hold kept for a party yet to take it, as while a replacement's engine starts.
        var busy = true
        val audio = FakeFocus()
        val focus = SharedMusicFocus(audio) { busy }
        val first = focus.party()
        val next = focus.party()
        assertTrue(first.take(ReadAloudMusic.LOWER) {})
        first.release()
        assertEquals(0, audio.releases)
        // A call takes the focus: the kept hold is let go, not granted to the next.
        audio.lose()
        assertEquals(1, audio.releases)
        assertTrue(next.take(ReadAloudMusic.LOWER) {})
        assertEquals(listOf(ReadAloudMusic.LOWER, ReadAloudMusic.LOWER), audio.taken)
        audio.grants = false
        busy = false
        next.release()
        // Refused now: no hold left to grant it from.
        val third = focus.party()
        busy = true
        assertFalse(third.take(ReadAloudMusic.LOWER) {})
    }

    @Test
    fun theFocusLostWhileHeldStopsTheHolder() {
        val audio = FakeFocus()
        val focus = SharedMusicFocus(audio) { false }
        val party = focus.party()
        var stopped = 0
        party.take(ReadAloudMusic.LOWER) {
            stopped++
            party.release()
        }
        audio.lose()
        assertEquals(1, stopped)
        assertEquals(1, audio.releases)
    }
}
