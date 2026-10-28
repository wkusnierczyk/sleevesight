package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.SourceId
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * *Sources* and *Genres* on the settings screen (#84, #140, #444): a source's switch, with what it
 * is, its site, and the key it lacks; the web search engine as a source of its own; *Show genres*;
 * and a genre source's switch that cannot act shows off, cannot be moved, and says why.
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

    /** A row whose text holds [id]'s, as a summary does among its lines. */
    private fun holding(id: Int) = holding(text(id))

    private fun holding(text: String) = hasText(text, substring = true)

    private fun absent(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    /** What a row needing the key of [service] says. */
    private fun needsKey(service: Int) = app.getString(R.string.settings_needs_key, text(service))

    private fun show(
        section: SettingsSection,
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
            waitForNode(hasText(text(R.string.settings_title)))
            compose.openSection(section)
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

    @Test
    fun theMusicBrainzLabelsAreASwitchOnByDefault() =
        show(SettingsSection.GENRES) {
            val row = compose.onNode(holding(R.string.settings_labels_musicbrainz_summary)).performScrollTo()
            row.assertIsOn()
            row.performClick()
            waitFor("only Last.fm labels stored") { current().labelSources == setOf(LabelSource.LASTFM) }
            waitForNode(holding(R.string.settings_labels_musicbrainz_summary) and isOff())
        }

    @Test
    fun lastFmsLabelsWaitForItsKey() =
        show(SettingsSection.GENRES) {
            // Without the key, the switch shows off, cannot move, and says where the key goes.
            val needs = needsKey(R.string.settings_source_lastfm)
            waitForNode(holding(needs))
            val waiting = compose.onNode(holding(needs)).performScrollTo()
            waiting.assertIsOff()
            waiting.assertIsNotEnabled()

            runBlocking { secrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
            waitFor("the Last.fm labels no longer waiting for the key") { absent(holding(needs)) }
            val label = compose.onNode(holding(R.string.settings_labels_lastfm_summary)).performScrollTo()
            label.assertIsOn()
            label.assertIsEnabled()
            label.performClick()
            waitFor("only MusicBrainz labels stored") { current().labelSources == setOf(LabelSource.MUSICBRAINZ) }
        }

    @Test
    fun aLabelSourceOffUnderSourcesCannotBeSwitched() =
        show(SettingsSection.GENRES) {
            runBlocking { settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.MUSICBRAINZ) } }
            waitForNode(holding(R.string.settings_labels_source_off))
            compose.onNode(holding(R.string.settings_labels_source_off)).performScrollTo().assertIsNotEnabled()
            // The choice itself is kept for when MusicBrainz is back on.
            assertEquals(Settings.DEFAULT_LABEL_SOURCES, current().labelSources)
        }

    @Test
    fun showGenresOffAsksNoSourceAndGreysTheirSwitches() =
        show(SettingsSection.GENRES) {
            val show = compose.onNode(hasText(text(R.string.settings_show_genres))).performScrollTo()
            show.assertIsOn()
            show.performClick()
            waitFor("genres stored off") { !current().showGenres }
            // No source gives labels, whatever its own switch, and each says why.
            assertTrue(LabelSource.entries.none { current().givesLabels(it) })
            waitForNode(holding(R.string.settings_labels_genres_off))
            compose.onNode(holding(R.string.settings_labels_musicbrainz_summary)).assertIsOff().assertIsNotEnabled()
            assertEquals(Settings.DEFAULT_LABEL_SOURCES, current().labelSources)

            compose.onNode(hasText(text(R.string.settings_show_genres))).performScrollTo().performClick()
            waitFor("genres stored on") { current().showGenres }
            waitForNode(holding(R.string.settings_labels_musicbrainz_summary) and isOn())
        }

    @Test
    fun lastFmsLabelsSayWhenItsKeyCannotBeRead() {
        val dataStore = stores.open("unreadable-lastfm")
        runBlocking { SecretStore(dataStore, testCipher()).set(SecretId.LASTFM, Secret("lastfm-key")) }
        // Another key than the one that sealed it, as after the Keystore lost the first.
        show(SettingsSection.GENRES, secretStore = SecretStore(dataStore, testCipher())) {
            val unreadable = app.getString(R.string.settings_key_unreadable_note, text(R.string.settings_source_lastfm))
            waitForNode(holding(unreadable))
            val waiting = compose.onNode(holding(unreadable)).performScrollTo()
            waiting.assertIsOff()
            waiting.assertIsNotEnabled()
            assertTrue(absent(holding(needsKey(R.string.settings_source_lastfm))))
        }
    }

    @Test
    fun exaWebSearchIsASourceOfItsOwn() =
        show(SettingsSection.SOURCES) {
            // No key yet: it says so, above the link to where the key goes, and its switch, stored
            // on as by default, shows off and cannot be moved (#667).
            val exaName = text(R.string.settings_key_recipient_exa)
            val needs = app.getString(R.string.settings_needs_key_linked, exaName)
            waitForNode(holding(needs))
            val exa = compose.onNode(holding(R.string.settings_engine_exa_summary)).performScrollTo()
            exa.assertIsOff().assertIsNotEnabled()

            // Saved, the key makes the switch usable, and leaves it as it was stored.
            runBlocking { secrets.set(SecretId.EXA, Secret("exa-key")) }
            waitFor("the key notice gone") { absent(holding(needs)) }
            exa.assertIsOn().assertIsEnabled()

            exa.performClick()
            waitFor("Exa stored off") { WebEngine.EXA !in current().webEngines }
            assertFalse(current().isEnabled(SourceId.WEB))

            exa.performClick()
            waitFor("Exa stored on") { WebEngine.EXA in current().webEngines }
            assertTrue(current().isEnabled(SourceId.WEB))
        }

    @Test
    fun exaSwitchedOnTurnsWebSearchOnToo() {
        // As an earlier version could leave it: *Web search* off, its engine on.
        runBlocking {
            secrets.set(SecretId.EXA, Secret("exa-key"))
            settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.WEB) }
        }
        show(SettingsSection.SOURCES) {
            waitForNode(holding(R.string.settings_engine_exa_summary) and isEnabled())
            val exa = compose.onNode(holding(R.string.settings_engine_exa_summary)).performScrollTo()
            exa.assertIsOff()
            exa.performClick()
            waitFor("web search on with Exa") { current().isEnabled(SourceId.WEB) }
        }
    }

    @Test
    fun aSourcesWebsiteOpensInTheBrowser() =
        show(SettingsSection.SOURCES) {
            val link = app.getString(R.string.settings_open_website_of, text(R.string.settings_source_musicbrainz))
            compose.onNodeWithContentDescription(link).performScrollTo().performClick()

            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://musicbrainz.org/", started?.dataString)
            // The AI service's knowledge has no site of its own.
            val model = app.getString(R.string.settings_open_website_of, text(R.string.settings_source_model))
            assertTrue(absent(hasContentDescription(model)))
        }

    @Test
    fun lastFmsGenresRowOpensItsWebsite() =
        show(SettingsSection.GENRES) {
            // As under *Sources* (#545); offered before its key is saved, as MusicBrainz's is.
            val link = app.getString(R.string.settings_open_website_of, text(R.string.settings_source_lastfm))
            compose.onNodeWithContentDescription(link).performScrollTo().performClick()

            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://www.last.fm/", started?.dataString)
        }

    @Test
    fun aSourceSwitchedOffIsStored() =
        show(SettingsSection.SOURCES) {
            compose.onNode(holding(R.string.settings_source_wikipedia_summary)).performScrollTo().performClick()
            waitFor("Wikipedia stored off") { SourceSwitch.WIKIPEDIA !in current().enabledSources }
            assertEquals(Settings.DEFAULT_WEB_ENGINES, current().webEngines)
        }
}
