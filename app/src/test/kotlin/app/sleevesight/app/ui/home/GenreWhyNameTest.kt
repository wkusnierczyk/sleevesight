package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Is this …?* for a genre the AI's list leaves out (#1117), its name holding what reads as a
 * placeholder (#1091): the name shows as written, formatted in once as the question's argument.
 */
@RunWith(RobolectricTestRunner::class)
class GenreWhyNameTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun assertShownAsWritten(genre: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { AskBlock(app.getString(R.string.genre_ask, genre), onClick = {}) }
            }
            compose.onNodeWithText("Is this $genre?", substring = true, useUnmergedTree = true).assertExists()
            val line = "Only MusicBrainz gives this genre; your AI service’s list for this song leaves it out."
            compose.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun aNameHoldingAPlaceholderShowsAsText() = assertShownAsWritten("rock %1\$s")

    @Test
    fun aNameHoldingAPlaceholderTooLargeToReadShowsAsTextRatherThanFail() = assertShownAsWritten("rock %99999999999\$s")
}
