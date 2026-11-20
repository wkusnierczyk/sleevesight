package app.sleevesight.app.speech

import app.sleevesight.core.settings.ReadAloudMusic
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * *Voice*'s samples (#787): a sample is read in the voice asked for, the music lowered under it, a
 * reading under way stopped first; it ends, the music given back, at its end, on a stop, when
 * another sample replaces it, when another app takes the sound, and when the engine is closed; one
 * that cannot be read (no engine, the focus refused) ends at once. And the engine's voices are
 * read as it opens, or that it has none.
 */
class ReadAloudVoicesTest {
    private val speech = FakeSpeech()
    private val focus = FakeFocus()
    private var voiceOf: () -> String? = { null }
    private val voicesAtSpeak = mutableListOf<String?>()
    private var readingsStopped = 0

    // The fake engine, which records the voice asked for whenever it is asked to speak.
    private val recording =
        object : Speech by speech {
            override fun speak(
                parts: List<String>,
                locale: java.util.Locale,
                done: () -> Unit,
                failed: () -> Unit,
            ) {
                voicesAtSpeak += voiceOf()
                speech.speak(parts, locale, done, failed)
            }
        }

    private val voices =
        SystemReadAloudVoices(
            speechOf = { voice ->
                voiceOf = voice
                recording
            },
            focus = focus,
            online = flowOf(true),
            before = { readingsStopped++ },
        )

    private var ends = 0

    private fun sample(id: String?) =
        voices.sample(id, "This is how your insights will sound in this voice.") { ends++ }

    @Test
    fun aSampleIsReadInTheVoiceAskedForTheMusicLoweredAReadingStoppedFirst() {
        sample("en-gb-x-gba")
        assertEquals(1, readingsStopped)
        assertEquals(listOf("en-gb-x-gba"), voicesAtSpeak)
        assertEquals(listOf(listOf("This is how your insights will sound in this voice.")), speech.spoken)
        assertEquals(listOf(ReadAloudMusic.LOWER), focus.taken)
        speech.finish()
        assertEquals(1, ends)
        assertFalse(focus.held)
    }

    @Test
    fun thePhonesChoiceIsAskedForAsNoVoice() {
        sample(null)
        assertEquals(listOf<String?>(null), voicesAtSpeak)
    }

    @Test
    fun aStopEndsItAndGivesTheMusicBack() {
        sample("v")
        voices.stopSample()
        assertEquals(1, ends)
        assertEquals(1, speech.stops)
        assertFalse(focus.held)
        // A late end from the engine is not told again.
        speech.finish()
        assertEquals(1, ends)
    }

    @Test
    fun anotherSampleEndsTheOneUnderWay() {
        sample("a")
        sample("b")
        assertEquals(1, ends)
        assertEquals(listOf("a", "b"), voicesAtSpeak)
        speech.finish()
        assertEquals(2, ends)
    }

    @Test
    fun anotherAppTakingTheSoundEndsIt() {
        sample("v")
        focus.lose()
        assertEquals(1, ends)
        assertFalse(focus.held)
    }

    @Test
    fun closingEndsItAndReleasesTheEngine() {
        sample("v")
        voices.close()
        assertEquals(1, ends)
        assertEquals(1, speech.closes)
    }

    @Test
    fun withNoEngineOrTheFocusRefusedItEndsAtOnceUnread() {
        speech.opens = false
        sample("v")
        assertEquals(1, ends)
        speech.opens = true
        focus.grants = false
        sample("v")
        assertEquals(2, ends)
        assertEquals(emptyList<List<String>>(), speech.spoken)
    }

    @Test
    fun theEnginesVoicesAreReadAsItOpensOrThatItHasNone() {
        val told = mutableListOf<VoicesLoad>()
        voices.load { told += it }
        speech.opens = false
        voices.load { told += it }
        assertEquals(listOf(VoicesLoad.Loaded(emptyList(), null), VoicesLoad.NoEngine), told)
    }
}
