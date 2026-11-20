package app.sleevesight.app.speech

import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

// An engine that starts only when told ([start]), as a real one starts on its own thread.
internal class SlowSpeech(
    val inner: FakeSpeech = FakeSpeech(),
) : Speech by inner {
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    override fun open(ready: (Boolean) -> Unit) {
        waiting += ready
    }

    fun start() {
        val callers = waiting.toList()
        waiting.clear()
        callers.forEach { inner.open(it) }
    }
}

/**
 * A sample of *Voice* (#787) and a reading aloud are never heard together, as the app wires them
 * (`AppGraph`): whichever starts stops the other, while it is read or while its engine is still
 * starting, each engine starting late here ([SlowSpeech]); the one stopped gives its music back.
 */
class SampleAndReadingTest {
    private val readingSpeech = SlowSpeech()
    private val sampleSpeech = SlowSpeech()
    private val readingFocus = FakeFocus()
    private val sampleFocus = FakeFocus()
    private lateinit var voices: SystemReadAloudVoices
    private val readAloud =
        ReadAloud(
            readingSpeech,
            readingFocus,
            MutableStateFlow<Settings?>(Settings()),
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Unconfined),
            before = { voices.stopSample() },
        ) { Locale.UK }

    init {
        voices =
            SystemReadAloudVoices(
                speechOf = { sampleSpeech },
                focus = sampleFocus,
                online = flowOf(true),
                before = { readAloud.stop() },
                locale = { Locale.UK },
            )
    }

    private val insight = written("Sundown was recorded in 1973 [MB1].")
    private val shown = track(insight = insight)
    private var sampleEnds = 0

    private fun sample() =
        voices.sample("en-gb-x-gba", "This is how your insights will sound in this voice.") {
            sampleEnds++
        }

    @Test
    fun aReadingStartedWhileTheSampleEngineStartsLeavesTheSampleUnread() {
        sample()
        readAloud.readInsight(shown, insight)
        assertEquals(1, sampleEnds)
        readingSpeech.start()
        sampleSpeech.start()
        assertEquals(listOf(listOf("Sundown was recorded in 1973.")), readingSpeech.inner.spoken)
        assertEquals(emptyList<List<String>>(), sampleSpeech.inner.spoken)
        assertFalse(sampleFocus.held)
    }

    @Test
    fun aReadingStartedWhileTheSampleIsReadStopsIt() {
        sample()
        sampleSpeech.start()
        assertEquals(1, sampleSpeech.inner.spoken.size)
        readAloud.readInsight(shown, insight)
        assertEquals(1, sampleEnds)
        assertEquals(1, sampleSpeech.inner.stops)
        assertFalse(sampleFocus.held)
        readingSpeech.start()
        assertEquals(1, readingSpeech.inner.spoken.size)
    }

    @Test
    fun aSampleStartedWhileTheReadingEngineStartsLeavesTheReadingUnread() {
        readAloud.readInsight(shown, insight)
        sample()
        assertNull(readAloud.reading.value)
        readingSpeech.start()
        sampleSpeech.start()
        assertEquals(emptyList<List<String>>(), readingSpeech.inner.spoken)
        assertEquals(1, sampleSpeech.inner.spoken.size)
        assertFalse(readingFocus.held)
    }

    @Test
    fun aSampleStartedWhileTheReadingIsReadStopsIt() {
        readAloud.readInsight(shown, insight)
        readingSpeech.start()
        assertEquals(1, readingSpeech.inner.spoken.size)
        sample()
        assertNull(readAloud.reading.value)
        assertEquals(1, readingSpeech.inner.stops)
        assertFalse(readingFocus.held)
        sampleSpeech.start()
        assertEquals(1, sampleSpeech.inner.spoken.size)
    }
}
