package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontStyle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The track's header (#438): the title, the artists, and the album with the year after it. */
@RunWith(RobolectricTestRunner::class)
class KeyDataTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(
        playing: NowPlaying,
        year: String?,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KeyData(normalizer.normalize(playing), playing, year) } }
            block()
        }
    }

    @Test
    fun theYearFollowsTheAlbumInItalicsOnOneLine() =
        show(sundown, "1974") {
            // One text, so a screen reader reads the album and the year as one line.
            val text =
                compose
                    .onNodeWithText("Sundown, 1974")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
            assertEquals("Sundown", text.text.substring(italic.start, italic.end))
            assertEquals(0, compose.onAllNodesWithText("Released", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun withNoYearTheAlbumStandsAlone() =
        show(sundown, null) {
            // The title, and the album of the same name.
            assertEquals(2, compose.onAllNodesWithText("Sundown").fetchSemanticsNodes().size)
        }

    @Test
    fun withNoAlbumTheYearStandsAlone() =
        show(sundown.copy(album = null), "1974") {
            compose.onNodeWithText("1974").assertExists()
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertDoesNotExist()
        }

    @Test
    fun withNeitherTheLineSaysTheAlbumWasNotReported() =
        show(sundown.copy(album = " "), null) {
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertExists()
        }
}
