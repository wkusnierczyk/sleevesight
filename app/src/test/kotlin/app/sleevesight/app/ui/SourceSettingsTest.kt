package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Where to look*'s web search engines and *Genre labels* on the settings screen: a switch that
 * cannot act shows off, cannot be moved, and says why (#84, #140).
 */
@RunWith(RobolectricTestRunner::class)
class SourceSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }

    /** Where the screen saves, as the process's scope in the app: it outlives the screen. */
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun absent(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isEmpty()

    private fun show(
        secretStore: SecretStore = secrets,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secretStore, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            // The screen draws once the settings are read.
            waitForText(text(R.string.settings_title))
            scenario.block()
        }
    }

    /** The stored settings; a step that reads the screen after them waits for it too. */
    private fun current(): Settings = settings.now()

    /** Waits for [condition]; a timeout names [what]. */
    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    @Test
    fun theMusicBrainzLabelsAreASwitchOnByDefault() =
        show {
            val row = compose.onNodeWithText(text(R.string.settings_labels_musicbrainz_summary)).performScrollTo()
            row.assertIsOn()
            row.performClick()
            waitFor("only Last.fm labels stored") { current().labelSources == setOf(LabelSource.LASTFM) }
            waitForNode(hasText(text(R.string.settings_labels_musicbrainz_summary)) and isOff())
        }

    @Test
    fun lastFmsLabelsWaitForItsKey() =
        show {
            // Without the key, the switch shows off, cannot move, and says why.
            val waiting = compose.onNodeWithText(text(R.string.settings_labels_needs_key)).performScrollTo()
            waiting.assertIsOff()
            waiting.assertIsNotEnabled()

            runBlocking { secrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
            waitFor("the Last.fm labels no longer waiting for the key") {
                compose
                    .onAllNodesWithText(
                        text(R.string.settings_labels_needs_key),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            }
            // Last.fm under *Where to look*, under *Genre labels*, and its cache lifetime.
            val label = compose.onAllNodesWithText(text(R.string.settings_source_lastfm))[1].performScrollTo()
            label.assertIsOn()
            label.assertIsEnabled()
            label.performClick()
            waitFor("only MusicBrainz labels stored") { current().labelSources == setOf(LabelSource.MUSICBRAINZ) }
        }

    @Test
    fun aLabelSourceOffUnderWhereToLookCannotBeSwitched() =
        show {
            runBlocking { settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.MUSICBRAINZ) } }
            waitFor("the MusicBrainz labels shown off under *Where to look*") {
                compose
                    .onAllNodesWithText(
                        text(R.string.settings_labels_source_off),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.settings_labels_source_off)).performScrollTo().assertIsNotEnabled()
            // The choice itself is kept for when MusicBrainz is back on.
            assertEquals(Settings.DEFAULT_LABEL_SOURCES, current().labelSources)
        }

    @Test
    fun aWebSearchEngineIsASwitchUnderWebSearch() =
        show {
            val exa = compose.onNodeWithText(text(R.string.settings_engine_exa)).performScrollTo()
            exa.assertIsOn()
            exa.performClick()
            waitFor("no web engine stored") { current().webEngines.isEmpty() }
            // *Web search* on with no engine on searches nothing, and says so.
            waitForText(text(R.string.settings_source_web_no_engine))

            compose.onAllNodesWithText(text(R.string.settings_source_web))[0].performScrollTo().performClick()
            waitFor("web search stored off") { SourceSwitch.WEB !in current().enabledSources }
            waitForNode(hasText(text(R.string.settings_engine_exa)) and isNotEnabled())
            assertEquals(emptySet<WebEngine>(), current().webEngines)
            assertTrue(
                compose
                    .onAllNodesWithText(text(R.string.settings_source_web_no_engine))
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
        }

    @Test
    fun anEngineShowsOffWhileWebSearchIsOffAndSaysWhy() =
        show {
            runBlocking { settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.WEB) } }
            waitForText(text(R.string.settings_engine_web_off))
            val exa = compose.onNodeWithText(text(R.string.settings_engine_web_off)).performScrollTo()
            exa.assertIsOff()
            exa.assertIsNotEnabled()
            // The choice itself is kept for when web search is back on.
            assertEquals(Settings.DEFAULT_WEB_ENGINES, current().webEngines)

            runBlocking { settings.update { it.copy(enabledSources = it.enabledSources + SourceSwitch.WEB) } }
            waitForNode(hasText(text(R.string.settings_engine_exa)) and isOn())
            assertTrue(absent(R.string.settings_engine_web_off))
        }

    @Test
    fun lastFmsLabelsSayWhenItsKeyCannotBeRead() {
        val dataStore = stores.open("unreadable-lastfm")
        runBlocking { SecretStore(dataStore, testCipher()).set(SecretId.LASTFM, Secret("lastfm-key")) }
        // Another key than the one that sealed it, as after the Keystore lost the first.
        show(secretStore = SecretStore(dataStore, testCipher())) {
            waitForText(text(R.string.settings_labels_key_unreadable))
            val waiting = compose.onNodeWithText(text(R.string.settings_labels_key_unreadable)).performScrollTo()
            waiting.assertIsOff()
            waiting.assertIsNotEnabled()
            assertTrue(absent(R.string.settings_labels_needs_key))
        }
    }
}
