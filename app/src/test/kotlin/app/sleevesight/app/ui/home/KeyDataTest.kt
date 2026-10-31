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

/**
 * The track's header (#438): the title, the artists, and the album with its year after it (#556);
 * with no album reported, a line that says so, the first release being in the *Dates* panel (#724).
 */
@RunWith(RobolectricTestRunner::class)
class KeyDataTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(
        playing: NowPlaying,
        years: HeaderYears,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KeyData(normalizer.normalize(playing), playing, years) } }
            block()
        }
    }

    /** The words of [line]'s text in italics. */
    private fun italics(line: String): List<String> {
        val text =
            compose
                .onNodeWithText(line)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        return text.spanStyles
            .filter { it.item.fontStyle == FontStyle.Italic }
            .map { text.text.substring(it.start, it.end) }
    }

    @Test
    fun theYearFollowsTheAlbumInItalicsOnOneLine() =
        show(sundown, HeaderYears(album = "1974")) {
            // One text, so a screen reader reads the album and the year as one line.
            assertEquals(listOf("Sundown"), italics("Sundown, 1974"))
            assertEquals(0, compose.onAllNodesWithText("Released", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun withNoYearTheAlbumStandsAlone() =
        show(sundown.copy(album = "Gord's Gold"), HeaderYears()) {
            assertEquals(listOf("Gord's Gold"), italics("Gord's Gold"))
            compose.onNodeWithText("Gord's Gold, 1974").assertDoesNotExist()
            // No first release under it: that is in the *Dates* panel (#724).
            assertEquals(0, compose.onAllNodesWithText("released", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun withNoAlbumTheLineSaysTheAlbumWasNotReported() {
        show(sundown.copy(album = " "), HeaderYears()) {
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertExists()
        }
        show(sundown.copy(album = null), HeaderYears()) {
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertExists()
            assertEquals("Album not reported", app.getString(R.string.home_album_none))
        }
    }
}
