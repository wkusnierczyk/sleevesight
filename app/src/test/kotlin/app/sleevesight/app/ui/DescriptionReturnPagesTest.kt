package app.sleevesight.app.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.home.PAGE_BACK_TAG
import app.sleevesight.app.ui.home.PAGE_TITLE_TAG
import app.sleevesight.app.ui.home.awaitIdleHome
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.DescriptionsOnReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Descriptions on return* back from the pages the settings open (#874), through the app's own
 * navigation: *Credits*, *Diagnostics* and *What leaves the device*, each left by its back arrow or
 * Android's back. *Folded* folds the descriptions again, *Unfolded* opens them, *As left* keeps them,
 * as on opening the settings, as the choice is then, though changed on the settings before the page;
 * a rotation keeps them as they are, on the settings or on a page.
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionReturnPagesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val spin get() = text(R.string.settings_spin_logo_summary)

    /** A page the settings open, by the text of what opens it, and its title. */
    private enum class Page(
        val opener: Int,
        val title: Int,
    ) {
        CREDITS(R.string.credits_title, R.string.credits_title),
        DIAGNOSTICS(R.string.settings_diagnostics_open, R.string.diagnostics_title),
        WHAT_LEAVES(R.string.settings_what_leaves, R.string.home_what_leaves),
    }

    /**
     * The app after the first run, access on, developer mode and *Diagnostics* on so the
     * diagnostics are reached, and [mode] for *Descriptions on return*; on the settings, with
     * *Appearance*, *About* and *Developer* open.
     */
    private fun onSettings(
        mode: DescriptionsOnReturn,
        block: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        runBlocking {
            app.graph.settings.update {
                it.copy(descriptionsOnReturn = mode, developerMode = true, showDiagnostics = true)
            }
        }
        FirstRunAcknowledgement(app).acknowledge()
        val component = ComponentName(app, SleevesightListenerService::class.java)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", component.flattenToString())
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitIdleHome()
            compose.onNodeWithContentDescription(text(R.string.home_settings)).performClick()
            compose.openSection(SettingsSection.APPEARANCE)
            compose.openSection(SettingsSection.ABOUT)
            compose.openSection(SettingsSection.DEVELOPER)
            block(scenario)
        }
    }

    private fun awaitText(text: String) =
        compose.waitUntil(conditionDescription = text, timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    /** Opens [page] from the settings. */
    private fun open(page: Page) {
        awaitText(text(page.opener))
        compose.onNodeWithText(text(page.opener)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PAGE_TITLE_TAG).assertTextEquals(text(page.title))
    }

    /** Back from the page on screen: its back arrow, or Android's back ([system]). */
    private fun ActivityScenario<MainActivity>.back(system: Boolean) {
        if (system) {
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
        } else {
            compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
        }
        compose.waitForIdle()
        compose.waitUntil(conditionDescription = "the settings", timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithTag(PAGE_BACK_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    /** [page] opened from the settings and left, back to them. */
    private fun ActivityScenario<MainActivity>.visit(page: Page) {
        open(page)
        // Each page left both ways across the tests: the arrow for the credits, Android's back for the others.
        back(system = page != Page.CREDITS)
    }

    /**
     * The settings drawn again, as after a page or a rotation: they show nothing until their first
     * read of the stored settings, on DataStore's own threads, which `waitForIdle` does not wait for.
     */
    private fun description(words: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(DESCRIPTION_TAG) and hasText(words)
        compose.waitUntil(conditionDescription = "the description $words", timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun isOpen(words: String): Boolean {
        val state = description(words).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        return state == text(R.string.settings_description_expanded)
    }

    private fun tap(words: String) {
        description(words).performClick()
        compose.waitForIdle()
    }

    @Test
    fun foldedFoldsTheDescriptionsAgainBackFromEachPage() =
        onSettings(DescriptionsOnReturn.FOLDED) { scenario ->
            for (page in Page.entries) {
                tap(spin)
                assertTrue(isOpen(spin))
                scenario.visit(page)
                assertFalse("folded back from $page", isOpen(spin))
            }
        }

    @Test
    fun unfoldedOpensTheDescriptionsBackFromEachPage() =
        onSettings(DescriptionsOnReturn.UNFOLDED) { scenario ->
            for (page in Page.entries) {
                tap(spin)
                assertFalse("a tap folds it", isOpen(spin))
                scenario.visit(page)
                assertTrue("open back from $page", isOpen(spin))
            }
        }

    @Test
    fun asLeftKeepsEachAsLeftBackFromEachPage() =
        onSettings(DescriptionsOnReturn.AS_LEFT) { scenario ->
            assertFalse("the first visit starts folded", isOpen(spin))
            for (page in Page.entries) {
                tap(spin)
                assertTrue(isOpen(spin))
                scenario.visit(page)
                assertTrue("open as left, back from $page", isOpen(spin))
                tap(spin)
                scenario.visit(page)
                assertFalse("folded as left, back from $page", isOpen(spin))
            }
        }

    @Test
    fun aChoiceChangedOnTheSettingsAppliesBackFromAPage() =
        onSettings(DescriptionsOnReturn.AS_LEFT) { scenario ->
            tap(spin)
            val returning = hasContentDescription(text(R.string.settings_descriptions_on_return), substring = true)
            compose
                .onNode(hasText(text(R.string.settings_descriptions_folded)) and isSelectable() and returning)
                .performScrollTo()
                .performClick()
            // Idle first, so the tap's save runs before the store is read.
            compose.waitForIdle()
            compose.waitUntil(WAIT_MS) {
                app.graph.settings
                    .now()
                    .descriptionsOnReturn == DescriptionsOnReturn.FOLDED
            }
            compose.waitForIdle()
            assertTrue("left open until a return", isOpen(spin))
            scenario.visit(Page.CREDITS)
            assertFalse("folded back from the page", isOpen(spin))
        }

    @Test
    fun aRotationOnTheSettingsKeepsAnUnfoldUnderFoldedAndIsNoReturn() {
        onSettings(DescriptionsOnReturn.FOLDED) { scenario ->
            tap(spin)
            scenario.recreate()
            compose.waitForIdle()
            assertTrue("open as tapped, after a rotation under Folded", isOpen(spin))
            // Not taken for a return: the next page still folds it on the way back.
            scenario.visit(Page.CREDITS)
            assertFalse(isOpen(spin))
        }
    }

    @Test
    fun aRotationOnTheSettingsKeepsAFoldUnderUnfolded() {
        onSettings(DescriptionsOnReturn.UNFOLDED) { scenario ->
            tap(spin)
            scenario.recreate()
            compose.waitForIdle()
            assertFalse("folded as tapped, after a rotation under Unfolded", isOpen(spin))
        }
    }

    @Test
    fun aRotationOnAPageStillAppliesTheChoiceBackOnTheSettings() {
        onSettings(DescriptionsOnReturn.FOLDED) { scenario ->
            tap(spin)
            open(Page.DIAGNOSTICS)
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag(PAGE_TITLE_TAG).assertExists()
            scenario.back(system = false)
            assertFalse("folded back from the page, rotated on it", isOpen(spin))
        }
    }
}
