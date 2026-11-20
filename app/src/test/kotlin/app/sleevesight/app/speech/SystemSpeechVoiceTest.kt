package app.sleevesight.app.speech

import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * [SystemSpeech] in the voice chosen under *Voice* (#787): set after the language before each
 * English reading, its server version while online and its phone version offline; a voice gone,
 * or one needing the internet while offline, sets none, so the language's default (the phone's
 * choice) reads, with no error; nor does a reading in another language, or no voice chosen.
 */
@RunWith(RobolectricTestRunner::class)
class SystemSpeechVoiceTest {
    // An engine that records the language and voices set, in order, and lists [voices].
    private class Engine(
        val onInit: (Int) -> Unit,
        val voices: List<EngineVoice>,
    ) : SpeechEngine {
        val set = mutableListOf<String>()

        override fun setAudioAttributes(attributes: AudioAttributes) = Unit

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) = Unit

        override fun isLanguageAvailable(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setLanguage(locale: Locale) {
            set += "language ${locale.toLanguageTag()}"
        }

        override fun voices(): List<EngineVoice> = voices

        override fun defaultVoice(): String = "en-gb-x-gbb-local"

        override fun setVoice(name: String): Boolean {
            set += "voice $name"
            return true
        }

        override fun speak(
            text: String,
            queueMode: Int,
            utteranceId: String,
        ): Int = TextToSpeech.SUCCESS

        override fun stop() = Unit

        override fun shutdown() = Unit
    }

    private val gb =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("GB")
            .build()
    private val voices =
        listOf(
            EngineVoice("en-gb-x-gba-local", gb, 300, network = false),
            EngineVoice("en-gb-x-gba-network", gb, 400, network = true),
            EngineVoice("en-gb-x-gbd-network", gb, 400, network = true),
        )

    private var chosen: String? = null
    private var online = true
    private lateinit var engine: Engine
    private val speech =
        SystemSpeech(ApplicationProvider.getApplicationContext(), voice = { chosen }, online = { online }) { onInit ->
            Engine(onInit, voices).also { engine = it }
        }

    private fun read(locale: Locale = gb): List<String> {
        speech.open { }
        engine.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        engine.set.clear()
        speech.speak(listOf("text"), locale, {}, {})
        return engine.set.toList()
    }

    @Test
    fun theChosenVoicesServerVersionIsSetAfterTheLanguageWhileOnline() {
        chosen = "en-gb-x-gba"
        assertEquals(listOf("language en-GB", "voice en-gb-x-gba-network"), read())
    }

    @Test
    fun offlineItsPhoneVersionIsSet() {
        chosen = "en-gb-x-gba"
        online = false
        assertEquals(listOf("language en-GB", "voice en-gb-x-gba-local"), read())
    }

    @Test
    fun aVoiceNeedingTheInternetWhileOfflineSetsNoneAndThePhonesChoiceReads() {
        chosen = "en-gb-x-gbd"
        online = false
        assertEquals(listOf("language en-GB"), read())
    }

    @Test
    fun aVoiceGoneSetsNoneAndThePhonesChoiceReads() {
        chosen = "en-gb-x-gone"
        assertEquals(listOf("language en-GB"), read())
    }

    @Test
    fun noVoiceChosenSetsNone() {
        assertEquals(listOf("language en-GB"), read())
    }

    @Test
    fun aReadingInAnotherLanguageSetsNoVoice() {
        chosen = "en-gb-x-gba"
        assertEquals(listOf("language de"), read(Locale.GERMAN))
    }

    @Test
    fun theOpenEnginesVoicesAndDefaultAreListed() {
        read()
        assertEquals(voices, speech.voices())
        assertEquals("en-gb-x-gbb-local", speech.defaultVoice())
    }
}
