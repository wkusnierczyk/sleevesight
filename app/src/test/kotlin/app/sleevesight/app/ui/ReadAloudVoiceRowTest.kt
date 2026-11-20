package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.speech.EngineVoice
import app.sleevesight.app.speech.ReadAloudVoices
import app.sleevesight.app.speech.VoicesLoad
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.ROW_NOTE_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.VOICE_LISTEN_TAG
import app.sleevesight.app.ui.settings.voicePlayTag
import app.sleevesight.core.settings.ReadAloudVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * *Voice* under *Read aloud* (#787): the first row under the heading, *The phone's choice* by
 * default; its dialog lists the engine's English voices under their countries' headings, the
 * phone's first, "Voice 1", "Voice 2" within each, marked by quality; choosing one stores it with
 * its name; the *Listen* pill and each voice's play button read the sample in it. Its notes: a
 * voice needing the internet while offline; a voice gone (the row then *The phone's choice*); no
 * English voice and no engine, where the row dims. The texts written out are the picked ones,
 * checked word for word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class ReadAloudVoiceRowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private class FakeVoices(
        var load: VoicesLoad,
        online: Boolean = true,
    ) : ReadAloudVoices {
        override val online = MutableStateFlow(online)
        val samples = mutableListOf<Pair<String?, String>>()

        override fun load(loaded: (VoicesLoad) -> Unit) = loaded(load)

        override fun sample(
            id: String?,
            text: String,
            ended: () -> Unit,
        ) {
            samples += id to text
        }

        override fun stopSample() = Unit

        override fun close() = Unit
    }

    private val us =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("US")
            .build()
    private val gb =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("GB")
            .build()
    private val engine =
        listOf(
            EngineVoice("en-gb-x-gba-local", gb, 300, network = false),
            EngineVoice("en-gb-x-gba-network", gb, 400, network = true),
            EngineVoice("en-gb-x-gbd-network", gb, 400, network = true),
            EngineVoice("en-us-x-sfg-local", us, 400, network = false),
            EngineVoice("de-de-x-dea-local", Locale.GERMANY, 300, network = false),
        )

    private fun text(id: Int) = app.getString(id)

    private fun show(
        voices: FakeVoices,
        chosen: ReadAloudVoice? = null,
        block: () -> Unit,
    ) {
        runBlocking { settings.update { it.copy(readAloudVoice = chosen) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        readAloudVoices = voices,
                    ) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            waitFor(hasText(VOICE) and hasTestTag(ROW_TITLE_TAG))
            block()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun y(matcher: SemanticsMatcher) =
        compose
            .onNode(matcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .positionInRoot.y

    private fun row() = compose.onNode(hasText(VOICE) and hasTestTag(ROW_TITLE_TAG), useUnmergedTree = true)

    private fun note(text: String) =
        compose.onNode(hasTestTag(ROW_NOTE_TAG) and hasText(text), useUnmergedTree = true).performScrollTo()

    @Test
    fun theRowIsFirstUnderReadAloudWithThePhonesChoiceAndThePickedDescription() =
        show(FakeVoices(VoicesLoad.Loaded(engine, "en-us-x-sfg-local"))) {
            row().performScrollTo()
            val heading = y(hasText(text(R.string.settings_read_aloud)) and hasTestTag(ROW_TITLE_TAG))
            val voice = y(hasText(VOICE) and hasTestTag(ROW_TITLE_TAG))
            val music = y(hasText(text(R.string.settings_read_aloud_music)) and hasTestTag(ROW_TITLE_TAG))
            assertTrue(heading < voice && voice < music)
            waitFor(hasText(PHONE))
            waitFor(hasText(DESCRIPTION))
            compose.onNodeWithTag(VOICE_LISTEN_TAG).performScrollTo().assertIsEnabled()
            assertEquals(LINK, text(R.string.settings_read_aloud_voice_link))
            assertEquals("Listen", text(R.string.settings_read_aloud_voice_listen))
        }

    @Test
    fun theDialogListsTheEnglishVoicesUnderTheirCountriesThePhonesFirst() =
        show(FakeVoices(VoicesLoad.Loaded(engine, "en-us-x-sfg-local"))) {
            row().performScrollTo().performClick()
            waitFor(isDialog())
            val us = y(hasText("United States") and isHeading())
            val uk = y(hasText("United Kingdom") and isHeading())
            assertTrue("the phone's country first", us < uk)
            // The phone's choice, with the voice it is now.
            waitFor(hasText("Now Voice 1 (United States)"))
            compose.onNode(hasText(PHONE) and isDialogItem(), useUnmergedTree = false).assertIsSelected()
            // Voice 1 and 2 of the United Kingdom, marked; no German voice, and no engine id.
            waitFor(hasText("higher quality online"))
            waitFor(hasText("higher quality · needs the internet"))
            assertTrue(compose.onAllNodesWithText("en-gb-x-gba", substring = true).fetchSemanticsNodes().isEmpty())
            assertEquals(2, compose.onAllNodesWithText("Voice 1").fetchSemanticsNodes().size)
            assertEquals(1, compose.onAllNodesWithText("Voice 2").fetchSemanticsNodes().size)
        }

    @Test
    fun choosingAVoiceStoresItWithItsName() =
        show(FakeVoices(VoicesLoad.Loaded(engine, null))) {
            row().performScrollTo().performClick()
            waitFor(isDialog())
            compose.onNode(hasContentDescriptionStart("Voice 1, United Kingdom")).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().readAloudVoice != null }
            assertEquals(ReadAloudVoice("en-gb-x-gba", "Voice 1 (United Kingdom)"), settings.now().readAloudVoice)
            waitFor(hasText("Voice 1 (United Kingdom)"))
        }

    @Test
    fun theListenPillAndAVoicesPlayButtonReadTheSampleInIt() {
        val voices = FakeVoices(VoicesLoad.Loaded(engine, null))
        show(voices, ReadAloudVoice("en-gb-x-gba", "Voice 1 (United Kingdom)")) {
            compose.onNodeWithTag(VOICE_LISTEN_TAG).performScrollTo().performClick()
            row().performScrollTo().performClick()
            waitFor(isDialog())
            compose.onNodeWithTag(voicePlayTag("en-gb-x-gbd")).performScrollTo().performClick()
            compose.onNodeWithTag(voicePlayTag(null)).performScrollTo().performClick()
            assertEquals(listOf("en-gb-x-gba" to SAMPLE, "en-gb-x-gbd" to SAMPLE, null to SAMPLE), voices.samples)
        }
    }

    @Test
    fun aVoiceNeedingTheInternetWhileOfflineIsSaidUnderTheRow() =
        show(
            FakeVoices(VoicesLoad.Loaded(engine, null), online = false),
            ReadAloudVoice("en-gb-x-gbd", "Voice 2 (United Kingdom)"),
        ) {
            waitFor(hasText(OFFLINE))
            note(OFFLINE)
        }

    @Test
    fun aVoiceGoneShowsThePhonesChoiceWithANote() =
        show(FakeVoices(VoicesLoad.Loaded(engine, null)), ReadAloudVoice("en-gb-x-gone", "Voice 4 (United Kingdom)")) {
            waitFor(hasText(GONE))
            note(GONE)
            waitFor(hasText(PHONE))
        }

    @Test
    fun aVoiceNamedByAFileIsShownCutWithNoFormatCharacter() {
        // Loaded from a settings file: named by its id, the file's text (#1482).
        val id = "\u202E" + "v".repeat(200)
        val cut = "v".repeat(79) + "\u2026"
        show(FakeVoices(VoicesLoad.Loading), ReadAloudVoice(id, id)) {
            waitFor(hasText(cut))
        }
        show(FakeVoices(VoicesLoad.Loaded(engine, null)), ReadAloudVoice(id, id)) {
            waitFor(hasText(app.getString(R.string.settings_read_aloud_voice_gone, cut)))
            assertTrue(compose.onAllNodes(hasText("\u202E", substring = true)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun noEnglishVoiceDimsTheRowWithANote() =
        show(FakeVoices(VoicesLoad.Loaded(engine.filter { it.locale.language == "de" }, null))) {
            waitFor(hasText(NO_VOICE))
            note(NO_VOICE)
            compose.onNodeWithTag(VOICE_LISTEN_TAG).performScrollTo().assertIsNotEnabled()
        }

    @Test
    fun noEngineDimsTheRowWithANote() =
        show(FakeVoices(VoicesLoad.NoEngine), ReadAloudVoice("en-gb-x-gba", "Voice 1 (United Kingdom)")) {
            waitFor(hasText(NO_ENGINE))
            note(NO_ENGINE)
            waitFor(hasText(PHONE))
            compose.onNodeWithTag(VOICE_LISTEN_TAG).performScrollTo().assertIsNotEnabled()
        }

    private fun isDialogItem() =
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)

    private fun hasContentDescriptionStart(start: String) =
        SemanticsMatcher("content description starts with $start") { node ->
            node.config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }
                ?.firstOrNull()
                ?.startsWith(start) == true
        }

    private companion object {
        const val VOICE = "Voice"
        const val PHONE = "The phone’s choice"
        const val LINK = "Android’s text-to-speech settings"
        const val DESCRIPTION =
            "The voice that reads insights aloud, from the voices your phone’s text-to-speech engine has for " +
                "English. The phone’s choice is the engine’s own default. A voice marked higher quality online, " +
                "or needing the internet, is spoken by the engine’s server, which the text is sent to. Voices are " +
                "added, and the engine chosen, in Android’s text-to-speech settings."
        const val SAMPLE = "This is how your insights will sound in this voice."
        const val OFFLINE = "Voice 2 (United Kingdom) needs the internet. While offline, the phone’s choice reads."
        const val GONE = "Voice 4 (United Kingdom) is no longer on this phone. The phone’s choice reads instead."
        const val NO_VOICE =
            "Your phone’s text-to-speech engine has no English voice. One can be added in Android’s " +
                "text-to-speech settings."
        const val NO_ENGINE =
            "This phone has no text-to-speech engine. One can be set up in Android’s settings, under " +
                "Accessibility, Text-to-speech output."
    }
}
