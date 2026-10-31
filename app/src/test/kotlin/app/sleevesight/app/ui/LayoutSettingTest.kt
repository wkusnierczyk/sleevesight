package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Layout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Layout* (#447) under *Appearance*: *Expandable boxes* by default, or *Separate pages*, stored as
 * chosen; *Keep Sources as you left them*, which applies to the boxes only, is not offered under
 * *Separate pages*.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(block: () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.APPEARANCE)
            block()
        }

    // The choice's button in *Layout*'s row (#637).
    private fun button(option: Int) = compose.onNode(hasText(text(option)) and isSelectable())

    private fun choose(option: Int) {
        button(option).performScrollTo().performClick()
    }

    // The *Keep Sources as you left them* switch.
    private fun keepSources() = compose.onNode(hasText(text(R.string.settings_keep_sources)) and isToggleable())

    @Test
    fun theLayoutIsExpandableBoxesUntilSeparatePagesIsChosenAndBack() =
        show {
            assertEquals(Layout.EXPANDABLE_BOXES, settings.now().layout)
            // The row marks the current choice.
            button(R.string.settings_layout_boxes).performScrollTo().assertIsSelected()

            choose(R.string.settings_layout_pages)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.SEPARATE_PAGES }
            button(R.string.settings_layout_pages).assertIsSelected()
            button(R.string.settings_layout_boxes).assertIsNotSelected()

            choose(R.string.settings_layout_boxes)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.EXPANDABLE_BOXES }
        }

    @Test
    fun keepSourcesIsOfferedUnderExpandableBoxesOnlyAndKeptUnderSeparatePages() =
        show {
            keepSources()
                .performScrollTo()
                .assertIsEnabled()
                .assertIsOff()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().keepSourcesUnfolded }

            choose(R.string.settings_layout_pages)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.SEPARATE_PAGES }
            // Not offered, but kept as it was.
            keepSources().performScrollTo().assertIsNotEnabled().assertIsOn()
            assertTrue(settings.now().keepSourcesUnfolded)

            choose(R.string.settings_layout_boxes)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.EXPANDABLE_BOXES }
            keepSources().performScrollTo().assertIsEnabled().assertIsOn()
            assertTrue(settings.now().keepSourcesUnfolded)
        }
}
