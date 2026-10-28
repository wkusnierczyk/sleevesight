package app.sleevesight.app.speech

import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * [SystemSpeech]'s handling of the engine (#325): a `speak` the engine refuses ends the reading as
 * failed at once, once, and lets the engine go, so the next open binds afresh; an engine that is
 * no longer current (closed, or replaced while it started) is ignored, and shut down if it starts.
 */
@RunWith(RobolectricTestRunner::class)
class SystemSpeechTest {
    // An engine that starts when told ([start]) and refuses the utterances from [refuseFrom] on.
    private class FakeEngine(
        val onInit: (Int) -> Unit,
    ) : SpeechEngine {
        var refuseFrom = Int.MAX_VALUE
        val spoken = mutableListOf<String>()
        var shutdowns = 0
        var listener: UtteranceProgressListener? = null

        fun start(status: Int = TextToSpeech.SUCCESS) = onInit(status)

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
            this.listener = listener
        }

        override fun isLanguageAvailable(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setLanguage(locale: Locale) = Unit

        override fun speak(
            text: String,
            queueMode: Int,
            utteranceId: String,
        ): Int {
            if (spoken.size >= refuseFrom) return TextToSpeech.ERROR
            spoken += text
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        override fun shutdown() {
            shutdowns++
        }
    }

    private val made = mutableListOf<FakeEngine>()
    private val speech =
        SystemSpeech(ApplicationProvider.getApplicationContext()) { onInit -> FakeEngine(onInit).also { made += it } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun opened(): MutableList<Boolean> {
        val told = mutableListOf<Boolean>()
        speech.open { told += it }
        return told
    }

    @Test
    fun aSpeakTheEngineRefusesFailsTheReadingOnceAndTheNextOpenBindsAfresh() {
        val told = opened()
        made.single().start()
        idle()
        assertEquals(listOf(true), told)
        made.single().refuseFrom = 1

        var failures = 0
        var ends = 0
        speech.speak(listOf("One.", "Two.", "Three."), Locale.UK, { ends++ }, { failures++ })
        idle()
        assertEquals("failed at once, once", 1, failures)
        assertEquals(0, ends)
        assertEquals("nothing queued after the refusal", listOf("One."), made.single().spoken)
        assertEquals("the engine let go", 1, made.single().shutdowns)

        val again = opened()
        assertEquals("a new engine bound", 2, made.size)
        made[1].start()
        idle()
        assertEquals(listOf(true), again)
    }

    @Test
    fun anEngineClosedWhileItStartedIsShutDownAndTellsNoOne() {
        val told = opened()
        speech.close()
        val again = opened()
        assertEquals(2, made.size)
        // The first starts after the second was made: it is no longer current.
        made[0].start()
        idle()
        assertTrue(told.isEmpty())
        assertTrue(again.isEmpty())
        assertTrue("the stale engine shut down", made[0].shutdowns >= 1)

        made[1].start()
        idle()
        assertEquals(listOf(true), again)
        assertEquals(0, made[1].shutdowns)
    }

    @Test
    fun theProgressOfAnEngineLetGoIsIgnored() {
        opened()
        made.single().start()
        idle()
        var ends = 0
        speech.speak(listOf("One."), Locale.UK, { ends++ }, {})
        val listener = made.single().listener!!
        speech.close()
        listener.onDone("read-1")
        idle()
        assertEquals(0, ends)
    }
}
