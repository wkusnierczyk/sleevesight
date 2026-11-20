package app.sleevesight.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.settings.ReadAloudVoice
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * *Voice* (#787) as stored: *The phone's choice* by default, and for a settings file from before
 * it, stored as no key; a voice chosen under two keys of its own, its id and its name, read back
 * as it was written; a voice stored without its name named by its id; back to the phone's choice,
 * both keys removed.
 */
class ReadAloudVoiceCodecTest {
    private val voice = ReadAloudVoice("en-gb-x-gba", "Voice 1 (United Kingdom)")

    @Test
    fun theDefaultIsThePhonesChoiceStoredAsNoKey() {
        assertNull(Settings().readAloudVoice)
        assertNull(SettingsCodec.read(mutablePreferencesOf()).readAloudVoice)
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
    }

    @Test
    fun aVoiceChosenIsStoredUnderItsIdAndNameAndReadBackAsWritten() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(readAloudVoice = voice), prefs)
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("read_aloud.voice") to "en-gb-x-gba",
                stringPreferencesKey("read_aloud.voice_name") to "Voice 1 (United Kingdom)",
            ),
            prefs,
        )
        assertEquals(voice, SettingsCodec.read(prefs).readAloudVoice)
    }

    @Test
    fun aVoiceStoredWithoutItsNameIsNamedByItsId() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("read_aloud.voice") to "en-gb-x-gba")
        assertEquals(ReadAloudVoice("en-gb-x-gba", "en-gb-x-gba"), SettingsCodec.read(prefs).readAloudVoice)
    }

    @Test
    fun backToThePhonesChoiceRemovesBothKeys() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(readAloudVoice = voice), prefs)
        SettingsCodec.write(Settings(), prefs)
        assertEquals(mutablePreferencesOf(), prefs)
    }
}
