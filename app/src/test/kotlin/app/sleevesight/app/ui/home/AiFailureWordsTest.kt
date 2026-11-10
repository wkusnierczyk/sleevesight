package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.pipeline.ReplyFailures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The words for an AI call's failure (#1164): "no connection" only for no network; a connection that
 * failed on a working network has its own words, and a timeout keeps its own.
 */
@RunWith(RobolectricTestRunner::class)
class AiFailureWordsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun words(kind: AiErrorKind): String {
        var said = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { said = failureText(ReplyFailures.of(CompletionEvent.Error(kind))) }
            }
            compose.waitForIdle()
        }
        return said
    }

    @Test
    fun noConnectionIsSaidOnlyForNoNetwork() {
        assertEquals("no connection", words(AiErrorKind.OFFLINE))
    }

    @Test
    fun aConnectionThatFailedOnAWorkingNetworkSaysItDropped() {
        assertEquals("the connection dropped", words(AiErrorKind.CONNECTION_FAILED))
    }

    @Test
    fun aTimeoutKeepsItsOwnWords() {
        assertEquals("the AI service’s reply took too long, so the app stopped waiting", words(AiErrorKind.TIMED_OUT))
    }
}
