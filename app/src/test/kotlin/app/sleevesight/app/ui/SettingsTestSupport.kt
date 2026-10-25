package app.sleevesight.app.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.sleevesight.app.ui.settings.SettingsSection

/**
 * Unfolds [section] of the settings screen, as a tap on its heading does (#444): its rows are drawn
 * only then. Waits for the screen to show the heading first.
 */
internal fun ComposeTestRule.openSection(section: SettingsSection) {
    waitUntil(conditionDescription = "the ${section.name} heading", timeoutMillis = 5_000) {
        onAllNodesWithTag(section.tag).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(section.tag).performScrollTo().performClick()
    waitForIdle()
}
