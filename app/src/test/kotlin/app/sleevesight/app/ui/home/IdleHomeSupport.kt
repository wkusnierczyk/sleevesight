package app.sleevesight.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag

/**
 * Waits for Home with nothing playing (#627), which shows once the settings and the players have
 * loaded, off the main thread, and checks that it is on screen.
 */
internal fun ComposeTestRule.awaitIdleHome() {
    waitUntil(IDLE_HOME_WAIT) { onAllNodesWithTag(NOTHING_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(NOTHING_PLAYING_TAG).assertIsDisplayed()
}

private const val IDLE_HOME_WAIT = 5_000L
