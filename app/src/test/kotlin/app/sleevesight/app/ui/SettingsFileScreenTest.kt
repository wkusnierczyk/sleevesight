package app.sleevesight.app.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.LOAD_CONFIRM_TAG
import app.sleevesight.app.ui.settings.LOAD_DIALOG_TAG
import app.sleevesight.app.ui.settings.LOAD_FILE_TAG
import app.sleevesight.app.ui.settings.RESET_TAG
import app.sleevesight.app.ui.settings.SAVE_FILE_TAG
import app.sleevesight.app.ui.settings.SettingsFiles
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.LocalDate

/**
 * *Save to a file* and *Load from a file* (#815), under *Save and reset*: the file saved through the
 * picker, with no key in it; *Load settings?* with what changes, what is not loaded and why; *Load*,
 * *Cancel*, and the messages.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsFileScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The files the picker hands over, by their uri. */
    private val stored = mutableMapOf<Uri, String>()
    private val fileUri = Uri.parse("content://test/sleevesight-settings-2026-11-20.json")
    private var failWrites = false

    private val files =
        SettingsFiles(
            read = { uri -> stored[uri] ?: throw IOException("none") },
            write = { uri, text ->
                if (failWrites) throw IOException("full")
                stored[uri] = text
            },
            name = { uri -> uri.lastPathSegment },
        )

    /** The picker: each launch answered with [fileUri], its input kept. */
    private val launched = mutableListOf<Any?>()
    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launched += input
                dispatchResult(requestCode, fileUri)
            }
        }
    private val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }

    @After
    fun close() {
        saves.cancel()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun line(
        title: Int,
        now: Int,
        loaded: Int,
    ) = app.getString(R.string.settings_reset_line, app.getString(title), app.getString(now), app.getString(loaded))

    private fun screen(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        SettingsScreen(
                            settings,
                            secrets,
                            emptyMap(),
                            { CompletableDeferred(true) },
                            saves,
                            settingsFiles = files,
                        ) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.SAVE_AND_RESET)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(LOAD_FILE_TAG).fetchSemanticsNodes().isNotEmpty() }
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun openPreview(text: String) {
        stored[fileUri] = text
        tap(LOAD_FILE_TAG)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(LOAD_DIALOG_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun move(transform: (Settings) -> Settings) = runBlocking { settings.update(transform) }

    @Test
    fun theRowsFollowResetAndSayWhatTheyDo() =
        screen {
            fun top(tag: String) =
                compose
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .positionInRoot.y
            assertTrue(top(RESET_TAG) < top(SAVE_FILE_TAG))
            assertTrue(top(SAVE_FILE_TAG) < top(LOAD_FILE_TAG))
            assertTrue(shown("Save to a file"))
            assertTrue(
                shown(
                    "Saves your settings to a file you choose, to load here or on another phone. API keys, your " +
                        "history and the insights are never in it; keys move by the key QR code.",
                ),
            )
            assertTrue(shown("Load from a file"))
            assertTrue(
                shown(
                    "Loads settings saved by Sleevesight. You see what changes before anything does; settings the " +
                        "file doesn’t have keep their values.",
                ),
            )
        }

    @Test
    fun saveWritesTheSettingsToTheFilePickedWithNoKeyAndSaysWhere() {
        move { it.copy(theme = ThemeChoice.DARK) }
        runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-ant-secret-value")) }
        screen {
            tap(SAVE_FILE_TAG)
            compose.waitUntil(WAIT_MS) { shown("Settings saved to sleevesight-settings-2026-11-20.json.") }
            assertEquals("sleevesight-settings-${LocalDate.now()}.json", launched.single())
            val text = stored.getValue(fileUri)
            assertFalse(text, "sk-ant-secret-value" in text)
            val contents = SettingsFile.read(text)!!
            assertEquals(ThemeChoice.DARK, SettingsFile.load(contents, Settings(), false).settings.theme)
        }
    }

    @Test
    fun aFileThatCannotBeWrittenIsSaidSo() {
        failWrites = true
        screen {
            tap(SAVE_FILE_TAG)
            compose.waitUntil(WAIT_MS) { shown("The settings could not be saved to the file.") }
        }
    }

    @Test
    fun thePreviewListsWhatChangesWhatIsNotLoadedAndWhyAndLoadLoadsIt() {
        move { it.copy(theme = ThemeChoice.DARK, logoSpeed = LogoSpeed.RPM_45) }
        val before = settings.settings.now()
        val file =
            """{"file": "Sleevesight settings", "format": 1, "appVersion": "0.11.0", "settings": {
                "theme": "LIGHT", "pulseView": true, "musicBrainzWait": "90s"}}"""
        screen {
            openPreview(file)
            assertTrue(shown("Load settings?"))
            assertTrue(shown("From sleevesight-settings-2026-11-20.json, saved by Sleevesight 0.11.0."))
            assertTrue(shown("1 setting changes:"))
            assertTrue(
                shown(line(R.string.settings_theme, R.string.settings_theme_dark, R.string.settings_theme_light)),
            )
            assertTrue(shown("Not loaded:"))
            assertTrue(shown("pulseView: not a setting this version knows"))
            assertTrue(shown(app.getString(R.string.settings_musicbrainz_wait) + ": 90s: not one of its choices"))
            assertTrue(shown("Your API keys and history are never in a settings file."))
            assertEquals(before, settings.settings.now())
            compose.onNodeWithTag(LOAD_CONFIRM_TAG).assertIsEnabled().performClick()
            compose.waitUntil(WAIT_MS) { settings.settings.now().theme == ThemeChoice.LIGHT }
            // A setting the file does not have keeps its value.
            assertEquals(before.copy(theme = ThemeChoice.LIGHT), settings.settings.now())
            compose.waitUntil(WAIT_MS) { shown("1 setting loaded, 2 skipped.") }
        }
    }

    @Test
    fun aLoadWithNothingSkippedSaysHowManyLoaded() {
        val file = SettingsFile.write(Settings(theme = ThemeChoice.DARK, spinLogo = false), "0.10.0")
        screen {
            openPreview(file)
            assertTrue(shown("2 settings change:"))
            assertFalse(shown("Not loaded:"))
            compose.onNodeWithTag(LOAD_CONFIRM_TAG).performClick()
            compose.waitUntil(WAIT_MS) { shown("2 settings loaded.") }
            assertEquals(ThemeChoice.DARK, settings.settings.now().theme)
        }
    }

    @Test
    fun cancelLoadsNothing() {
        val before = settings.settings.now()
        screen {
            openPreview(SettingsFile.write(Settings(theme = ThemeChoice.DARK), "0.10.0"))
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(LOAD_DIALOG_TAG).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, settings.settings.now())
        }
    }

    @Test
    fun aFileThatMatchesTheSettingsSaysSoAndLoadIsDisabled() {
        screen {
            openPreview(SettingsFile.write(settings.settings.now(), "0.10.0"))
            assertTrue(shown("The file matches your settings; nothing changed."))
            compose.onNodeWithTag(LOAD_CONFIRM_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun developerSettingsInAFileLoadedWithoutDeveloperModeAreNamedNotLoaded() {
        val file =
            SettingsFile.write(
                Settings(developerMode = true, reportButton = ReportButton.SHOW, spinLogo = false),
                "0.10.0",
            )
        screen {
            openPreview(file)
            assertTrue(shown("Developer settings (Developer mode is off)"))
            compose.onNodeWithTag(LOAD_CONFIRM_TAG).performClick()
            compose.waitUntil(WAIT_MS) { !settings.settings.now().spinLogo }
            assertEquals(Settings().reportButton, settings.settings.now().reportButton)
            assertFalse(settings.settings.now().developerMode)
        }
    }

    @Test
    fun developerSettingsLoadInDeveloperMode() {
        move { it.copy(developerMode = true) }
        val file = SettingsFile.write(Settings(developerMode = true, reportButton = ReportButton.SHOW), "0.10.0")
        screen {
            openPreview(file)
            assertFalse(shown("Developer settings (Developer mode is off)"))
            compose.onNodeWithTag(LOAD_CONFIRM_TAG).performClick()
            compose.waitUntil(WAIT_MS) { settings.settings.now().reportButton == ReportButton.SHOW }
        }
    }

    @Test
    fun aFileThatHoldsNoSettingsIsSaidSo() {
        screen {
            stored[fileUri] = "{\"hello\": 1}"
            tap(LOAD_FILE_TAG)
            compose.waitUntil(WAIT_MS) { shown("This file holds no Sleevesight settings.") }
            assertTrue(compose.onAllNodesWithTag(LOAD_DIALOG_TAG).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun aFileThatCannotBeReadIsSaidSo() {
        screen {
            tap(LOAD_FILE_TAG)
            compose.waitUntil(WAIT_MS) { shown("The file could not be read.") }
        }
    }
}
