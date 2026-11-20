package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.HIDDEN_LINE_TAG
import app.sleevesight.app.ui.settings.HIDDEN_SHOW_TAG
import app.sleevesight.app.ui.settings.NOT_IN_VIEW_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SEARCH_MORE_SHOW_TAG
import app.sleevesight.app.ui.settings.SEARCH_MORE_TAG
import app.sleevesight.app.ui.settings.SEARCH_NONE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsView
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

/**
 * The settings' views on the screen (#1447): the switch under the title shows only the view's
 * settings and changes no value; a section's changed settings outside the view are said at its top
 * and shown, marked, on *Show*; a search says how many more match outside the view and shows them,
 * marked, on *Show them*; a link to a setting outside the view shows it, marked.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewsScreenTest {
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

    private fun text(id: Int) = app.getString(id)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun show(
        landOn: SettingsTarget? = null,
        onOpenCredits: () -> Unit = {},
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        onOpenCredits = onOpenCredits,
                        landOn = landOn,
                    ) {
                        it
                    }
                }
            }
            compose.waitUntil(WAIT_MS) {
                count(SettingsSection.PLAYERS.tag) + count(SettingsSection.PLAYERS.panelTag) ==
                    1
            }
            block()
        }
    }

    // A link in a line of text: its node is sized from the text's glyphs, which only Robolectric's
    // native graphics lays out, so a tap on it is `SettingsViewsPicturesTest`'s; here, its action.
    private fun follow(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun view(id: Int) {
        compose.onNode(hasText(text(id)) and hasAnyAncestor(hasTestTag("settings_view_switch"))).performClick()
        compose.waitForIdle()
    }

    private fun rowsOf(section: SettingsSection): List<String> =
        compose
            .onAllNodes(
                hasTestTag(ROW_TITLE_TAG) and hasAnyAncestor(hasTestTag(section.panelTag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun marks(): List<String> =
        compose
            .onAllNodesWithTag(NOT_IN_VIEW_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun lineOf(tag: String): String =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private fun set(transform: (Settings) -> Settings) = runBlocking { settings.update(transform) }

    @Test
    fun eachViewShowsItsSettingsAndTheLargerOnesMore() {
        set { it.copy(settingsView = SettingsView.BASIC) }
        show {
            tap(SettingsSection.APPEARANCE.tag)
            val basic = rowsOf(SettingsSection.APPEARANCE)
            assertEquals(
                listOf(R.string.settings_theme, R.string.settings_font, R.string.settings_layout).map(::text),
                basic,
            )
            view(R.string.settings_view_advanced)
            val advanced = rowsOf(SettingsSection.APPEARANCE)
            assertTrue(advanced.containsAll(basic))
            assertTrue(text(R.string.settings_theme_button) in advanced)
            assertFalse(text(R.string.settings_logo_speed) in advanced)
            view(R.string.settings_view_all)
            val all = rowsOf(SettingsSection.APPEARANCE)
            assertTrue(all.containsAll(advanced))
            assertTrue(text(R.string.settings_logo_speed) in all)
            assertEquals(emptyList<String>(), marks())
        }
    }

    @Test
    fun switchingViewsChangesNoSettingsValue() {
        set {
            it.copy(
                theme = ThemeChoice.DARK,
                logoSpeed = LogoSpeed.RPM_45,
                musicBrainzWait = 30.seconds,
                aiEfforts = mapOf(ProviderId.ANTHROPIC to ReasoningEffort.HIGH),
                pauseAutoWriteAfter = null,
            )
        }
        val before = runBlocking { settings.settings.first() }
        show {
            for (id in listOf(
                R.string.settings_view_basic,
                R.string.settings_view_advanced,
                R.string.settings_view_all,
            )) {
                view(id)
                val after = runBlocking { settings.settings.first() }
                assertEquals(before.copy(settingsView = after.settingsView), after)
            }
            assertEquals(SettingsView.ALL, runBlocking { settings.settings.first() }.settingsView)
        }
    }

    @Test
    fun aSectionSaysItsHiddenChangesAndShowsThemMarked() {
        set { it.copy(settingsView = SettingsView.BASIC, logoSpeed = LogoSpeed.RPM_45) }
        show {
            // *Players* hides nothing changed.
            tap(SettingsSection.PLAYERS.tag)
            assertEquals(0, count(HIDDEN_LINE_TAG))
            tap(SettingsSection.APPEARANCE.tag)
            assertEquals(1, count(HIDDEN_LINE_TAG))
            assertEquals("1 changed setting hidden. Show", lineOf(HIDDEN_LINE_TAG))
            assertFalse(text(R.string.settings_logo_speed) in rowsOf(SettingsSection.APPEARANCE))
            follow(HIDDEN_SHOW_TAG)
            assertEquals(0, count(HIDDEN_LINE_TAG))
            assertTrue(text(R.string.settings_logo_speed) in rowsOf(SettingsSection.APPEARANCE))
            assertEquals(listOf("Not in Basic"), marks())
            // Only the changed one: *Logo intro at start*, at its default, stays hidden.
            assertFalse(text(R.string.settings_intro) in rowsOf(SettingsSection.APPEARANCE))
        }
    }

    @Test
    fun aSearchSaysWhatMoreMatchesOutsideTheViewAndShowsThemMarked() {
        set { it.copy(settingsView = SettingsView.BASIC) }
        show {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("theme")
            compose.waitForIdle()
            assertEquals(listOf(text(R.string.settings_theme)), rowsOf(SettingsSection.APPEARANCE))
            assertEquals("1 more in Advanced. Show\u00A0them", lineOf(SEARCH_MORE_TAG))
            follow(SEARCH_MORE_SHOW_TAG)
            assertEquals(
                listOf(R.string.settings_theme, R.string.settings_theme_button).map(::text),
                rowsOf(SettingsSection.APPEARANCE),
            )
            assertEquals(listOf("Not in Basic"), marks())
            assertEquals(0, count(SEARCH_MORE_TAG))

            // Nothing in the view: no *No settings match*, only where the matches are.
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("logo speed")
            compose.waitForIdle()
            assertEquals(0, count(SEARCH_NONE_TAG))
            assertEquals("1 more in All. Show\u00A0them", lineOf(SEARCH_MORE_TAG))
        }
    }

    @Test
    fun leavingForAPageHidesWhatWasShownOutsideTheViewAgain() {
        set { it.copy(settingsView = SettingsView.BASIC, logoSpeed = LogoSpeed.RPM_45) }
        var opened = false
        show(onOpenCredits = { opened = true }) {
            tap(SettingsSection.APPEARANCE.tag)
            follow(HIDDEN_SHOW_TAG)
            assertTrue(text(R.string.settings_logo_speed) in rowsOf(SettingsSection.APPEARANCE))
            // *Credits*, under *About*: as the page opens, and back from it, the settings as drawn.
            tap(SettingsSection.ABOUT.tag)
            compose.onNode(hasText(text(R.string.credits_title))).performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(opened)
            assertFalse(text(R.string.settings_logo_speed) in rowsOf(SettingsSection.APPEARANCE))
            assertEquals("1 changed setting hidden. Show", lineOf(HIDDEN_LINE_TAG))
        }
    }

    @Test
    fun aShownSettingStaysWhileItIsSetBackToItsDefault() {
        set { it.copy(settingsView = SettingsView.BASIC, musicBrainzWait = 30.seconds) }
        show {
            tap(SettingsSection.SOURCES.tag)
            follow(HIDDEN_SHOW_TAG)
            val wait = text(R.string.settings_musicbrainz_wait)
            assertTrue(wait in rowsOf(SettingsSection.SOURCES))
            // As a drag to the default stop saves it: the row stays, marked, and no line comes back.
            set { it.copy(musicBrainzWait = Settings.DEFAULT_MUSICBRAINZ_WAIT) }
            compose.waitForIdle()
            assertTrue(wait in rowsOf(SettingsSection.SOURCES))
            assertEquals(listOf("Not in Basic"), marks())
            assertEquals(0, count(HIDDEN_LINE_TAG))
        }
    }

    @Test
    fun aLinkToAChangedSettingOutsideTheViewShowsItAndCountsItNoLongerHidden() {
        set {
            it.copy(
                settingsView = SettingsView.BASIC,
                aiModels = mapOf(ProviderId.ANTHROPIC to "claude-other"),
            )
        }
        show(landOn = SettingsTarget.model(ProviderId.ANTHROPIC)) {
            compose.waitUntil(WAIT_MS) { count(SettingsSection.AI_SERVICES.panelTag) == 1 }
            val name = text(R.string.settings_ai_provider_anthropic)
            assertTrue(app.getString(R.string.settings_ai_model_of, name) in rowsOf(SettingsSection.AI_SERVICES))
            assertEquals(0, count(HIDDEN_LINE_TAG))
        }
    }

    @Test
    fun aLinkToASettingOutsideTheViewShowsItMarked() {
        set { it.copy(settingsView = SettingsView.BASIC) }
        show(landOn = SettingsTarget.model(ProviderId.ANTHROPIC)) {
            compose.waitUntil(WAIT_MS) { count(SettingsSection.AI_SERVICES.panelTag) == 1 }
            val name = text(R.string.settings_ai_provider_anthropic)
            assertTrue(app.getString(R.string.settings_ai_model_of, name) in rowsOf(SettingsSection.AI_SERVICES))
            assertEquals(listOf("Not in Basic"), marks())
        }
    }
}
