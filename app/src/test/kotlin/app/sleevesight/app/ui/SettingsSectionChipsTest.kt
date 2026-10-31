package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.FontChoice
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
import org.robolectric.annotation.Config

/**
 * The settings' section chips (#649): every chip as wide as the widest name, each name whole in
 * it, with the app's font and the system's, at the usual font size and twice it; the focus moved
 * to a panel's *Close* on opening and back to the chip on closing; and a link to a setting in a
 * closed section opening its panel and landing on the setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SettingsSectionChipsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, and read on the main thread, as `SettingsKeyLinksTest`'s (#622).
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun show(
        font: FontChoice = FontChoice.APP_FONT,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false, font = font) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The layout of the name in [section]'s chip. */
    private fun nameIn(section: SettingsSection): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNode(hasAnyAncestor(hasTestTag(section.tag)) and hasText(text(section.title)), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    /** Every chip, *Developer*'s too, is as wide as the others, and shows its name whole. */
    private fun chipsAreEqualAndWhole() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        for (font in FontChoice.entries) {
            show(font) {
                waitForNode(hasTestTag(SettingsSection.DEVELOPER.tag))
                val widths =
                    SettingsSection.entries.associateWith {
                        compose
                            .onNodeWithTag(it.tag)
                            .performScrollTo()
                            .fetchSemanticsNode()
                            .size.width
                    }
                assertEquals("$font: $widths", 1, widths.values.toSet().size)
                for (section in SettingsSection.entries) {
                    val name = nameIn(section)
                    assertFalse("$font: ${section.name} cut short", name.hasVisualOverflow)
                    assertEquals("$font: ${section.name} on one line", 1, name.lineCount)
                }
            }
        }
    }

    @Test
    fun everyChipIsAsWideAsTheWidestNameWhichShowsWhole() = chipsAreEqualAndWhole()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeEveryChipIsAsWideAsTheWidestNameWhichShowsWhole() = chipsAreEqualAndWhole()

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheChipOnClosing() =
        show {
            val section = SettingsSection.HISTORY
            tap(section.tag)
            compose.onNodeWithTag(section.panelTag + ":close").assertIsFocused()
            tap(section.panelTag + ":close")
            compose.onNodeWithTag(section.tag).assertIsFocused()
        }

    @Test
    fun aLinkToASettingInAClosedSectionOpensItsPanelAndLandsOnIt() =
        show {
            tap(SettingsSection.SOURCES.tag)
            assertEquals(0, compose.onAllNodesWithTag(SettingsSection.SECRETS.panelTag).fetchSemanticsNodes().size)
            val target = SettingsTarget.key(SecretId.EXA)
            val name = text(R.string.settings_key_name_exa)
            // The sentence shows once the keys' states are read (#667).
            waitForNode(hasText(app.getString(R.string.settings_set_key, name)))
            compose
                .onNode(hasText(app.getString(R.string.settings_set_key, name)), useUnmergedTree = true)
                .performScrollTo()
                .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag }
            val focused = hasAnyAncestor(hasTestTag(target.tag)) and isFocused()
            waitForNode(focused)
            compose.onNode(focused).assertIsDisplayed()
            // Its section open as its panel, the chip gone; *Sources* still open too.
            assertTrue(compose.onAllNodesWithTag(SettingsSection.SECRETS.tag).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(SettingsSection.SECRETS.panelTag).assertExists()
            compose.onNodeWithTag(SettingsSection.SOURCES.panelTag).assertExists()
            compose
                .onNode(
                    hasAnyAncestor(hasTestTag(SettingsSection.SECRETS.panelTag)) and hasTestTag(target.tag),
                    useUnmergedTree = true,
                ).assertExists()
        }
}
