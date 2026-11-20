package app.sleevesight.app.speech

import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        // The engine's default language is American English: its voice is set until a language is.
        var current: String? = "en-us-x-sfg-local"

        // Whether it reports a language's voice by Google's alias for it.
        var aliases = false

        override fun setLanguage(locale: Locale) {
            set += "language ${locale.toLanguageTag()}"
            current = if (aliases) "${locale.toLanguageTag()}-language" else DEFAULTS[locale.toLanguageTag()]
        }

        override fun voices(): List<EngineVoice> = voices

        override fun currentVoice(): String? = current

        // Google's engine's default voice, of its own default language.
        override var defaultVoiceName: String? = "en-us-x-sfg-local"

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
    fun theOpenEnginesVoicesAreListed() {
        read()
        assertEquals(voices, speech.voices())
    }

    @Test
    fun thePhonesChoiceIsTheDefaultForTheReadingsLanguageNotTheEnginesDefaultLanguage() {
        speech.open { }
        engine.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        // The engine set to American English, the phone (and so the readings) to British English.
        assertEquals("en-us-x-sfg-local", engine.currentVoice())
        assertEquals("en-gb-x-gbb-local", speech.defaultVoice(gb))
    }

    @Test
    fun thePhonesChoiceReportedAsGooglesAliasIsTheEnginesDefaultVoiceOfTheSameLanguageAndCountry() {
        speech.open { }
        engine.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        engine.aliases = true
        engine.defaultVoiceName = "en-gb-x-gba-local"
        assertEquals("en-gb-x-gba-local", speech.defaultVoice(gb))
        // A default of another country, or an alias itself: none, and "Now …" is left out.
        engine.defaultVoiceName = "en-us-x-sfg-local"
        assertNull(speech.defaultVoice(gb))
        engine.defaultVoiceName = "en-GB-language"
        assertNull(speech.defaultVoice(gb))
    }

    private companion object {
        // The voice each language brings when set, as Google's engine has them.
        val DEFAULTS = mapOf("en-GB" to "en-gb-x-gbb-local", "en-US" to "en-us-x-sfg-local")
    }
}
