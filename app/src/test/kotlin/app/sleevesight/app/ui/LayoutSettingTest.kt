package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Layout* (#447) under *Appearance*: *Expandable boxes* by default, or *Separate pages*, stored as
 * chosen; *Sources on a new track* and *Song, Artist and Album on a new track* (#522), which apply to
 * the boxes only, are shown but not offered under *Separate pages*.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutSettingTest {
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

    // [option]'s button in the row titled [row] (#522: *Folded* is in several rows).
    private fun choice(
        row: Int,
        option: Int,
    ) = compose.onNode(hasContentDescription("${text(row)}: ${text(option)}") and isSelectable())

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
    fun theChoicesOnANewTrackAreOfferedUnderExpandableBoxesOnlyAndKeptUnderSeparatePages() =
        show {
            val sources = R.string.settings_sources_on_new_track
            val items = R.string.settings_items_on_new_track
            choice(sources, R.string.settings_new_track_folded).performScrollTo().assertIsSelected()
            choice(items, R.string.settings_new_track_folded).performScrollTo().assertIsSelected()
            choice(sources, R.string.settings_new_track_as_left).performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().sourcesOnNewTrack == NewTrackFold.AS_LEFT }
            choice(items, R.string.settings_new_track_unfolded).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().itemsOnNewTrack == NewTrackFold.UNFOLDED }

            choose(R.string.settings_layout_pages)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.SEPARATE_PAGES }
            // Shown, not offered, and kept as they were.
            choice(
                sources,
                R.string.settings_new_track_as_left,
            ).performScrollTo().assertIsNotEnabled().assertIsSelected()
            choice(items, R.string.settings_new_track_folded).performScrollTo().assertIsNotEnabled().performClick()
            assertEquals(NewTrackFold.UNFOLDED, settings.now().itemsOnNewTrack)

            choose(R.string.settings_layout_boxes)
            compose.waitUntil(WAIT_MS) { settings.now().layout == Layout.EXPANDABLE_BOXES }
            choice(items, R.string.settings_new_track_unfolded).performScrollTo().assertIsEnabled().assertIsSelected()
            assertEquals(NewTrackFold.AS_LEFT, settings.now().sourcesOnNewTrack)
        }
}
