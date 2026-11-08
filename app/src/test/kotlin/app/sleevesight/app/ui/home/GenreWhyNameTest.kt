package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.Unavailability
import app.sleevesight.app.ui.GENRE_LABELS_DOCS_URL
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A genre the AI's list lacks (#1059), its name holding what reads as a placeholder (#1091): the name
 * shows as written, and *See why.* stays the line's one link.
 */
@RunWith(RobolectricTestRunner::class)
class GenreWhyNameTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun assertShownAsWritten(genre: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    ReasonUnavailable("Why is this $genre?", genre, Unavailability.NOT_LISTED, openSettingsAt = {})
                }
            }
            val line =
                "The AI service doesn’t list $genre among this song’s genres. Only MusicBrainz does, and " +
                    "Sleevesight could not use that annotation. See why."
            val text =
                compose
                    .onNodeWithText(line, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val links = text.getLinkAnnotations(0, text.length)
            assertEquals(listOf(GENRE_LABELS_DOCS_URL), links.map { (it.item as LinkAnnotation.Url).url })
            assertEquals("See why.", text.substring(links.single().start, links.single().end))
        }
    }

    @Test
    fun aNameHoldingThePlaceholderOfTheLinkShowsAsTextNotASecondLink() = assertShownAsWritten("rock %1\$s")

    @Test
    fun aNameHoldingAPlaceholderTooLargeToReadShowsAsTextRatherThanFail() = assertShownAsWritten("rock %99999999999\$s")
}
