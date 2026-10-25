package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

    private fun choose(option: Int) {
        compose.onNodeWithText(text(R.string.settings_layout)).performScrollTo().performClick()
        compose.onNode(hasText(text(option)) and hasAnyAncestor(isDialog())).performClick()
    }

    // The *Keep Sources as you left them* switch.
    private fun keepSources() = compose.onNode(hasText(text(R.string.settings_keep_sources)) and isToggleable())

    @Test
    fun theLayoutIsExpandableBoxesUntilSeparatePagesIsChosenAndBack() =
        show {
            assertEquals(Layout.EXPANDABLE_BOXES, settings.now().layout)
            // The row says the current choice.
            compose.onNodeWithText(text(R.string.settings_layout_boxes)).performScrollTo()

            choose(R.string.settings_layout_pages)
            compose.waitUntil(5_000) { settings.now().layout == Layout.SEPARATE_PAGES }
            compose.onNodeWithText(text(R.string.settings_layout_pages)).performScrollTo()

            choose(R.string.settings_layout_boxes)
            compose.waitUntil(5_000) { settings.now().layout == Layout.EXPANDABLE_BOXES }
        }

    @Test
    fun keepSourcesIsOfferedUnderExpandableBoxesOnly() =
        show {
            keepSources().performScrollTo().assertIsEnabled()

            choose(R.string.settings_layout_pages)
            compose.waitUntil(5_000) { settings.now().layout == Layout.SEPARATE_PAGES }
            keepSources().performScrollTo().assertIsNotEnabled()

            choose(R.string.settings_layout_boxes)
            compose.waitUntil(5_000) { settings.now().layout == Layout.EXPANDABLE_BOXES }
            keepSources().performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { settings.now().keepSourcesUnfolded }
        }
}
