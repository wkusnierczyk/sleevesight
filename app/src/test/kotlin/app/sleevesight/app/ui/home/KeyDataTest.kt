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
 * The track's header (#438): the title, the artists, and the album with its year after it, and the
 * recording's first release under it when it is earlier (#556).
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
    fun anEarlierFirstReleaseIsSaidUnderTheAlbumAndItsYear() =
        show(sundown.copy(album = "Project Hail Mary"), HeaderYears(album = "2026", firstReleased = "1990")) {
            assertEquals(listOf("Project Hail Mary"), italics("Project Hail Mary, 2026"))
            // Its own line, in words, so a screen reader says what the year is.
            compose.onNodeWithText(app.getString(R.string.home_first_released, "1990")).assertExists()
            assertEquals("First released 1990", app.getString(R.string.home_first_released, "1990"))
        }

    @Test
    fun withTheAlbumsYearUnknownTheFirstReleaseIsSaidUnderTheAlbum() =
        show(sundown.copy(album = "Gord's Gold"), HeaderYears(firstReleased = "1974")) {
            assertEquals(listOf("Gord's Gold"), italics("Gord's Gold"))
            compose.onNodeWithText("Gord's Gold, 1974").assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.home_first_released, "1974")).assertExists()
        }

    @Test
    fun withNoYearTheAlbumStandsAlone() =
        show(sundown.copy(album = "Gord's Gold"), HeaderYears()) {
            assertEquals(listOf("Gord's Gold"), italics("Gord's Gold"))
            assertEquals(0, compose.onAllNodesWithText("First released", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun withNoAlbumTheFirstReleaseStandsAlone() =
        show(sundown.copy(album = null), HeaderYears(firstReleased = "1974")) {
            assertEquals(emptyList<String>(), italics(app.getString(R.string.home_first_released, "1974")))
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertDoesNotExist()
        }

    @Test
    fun withNeitherTheLineSaysTheAlbumWasNotReported() =
        show(sundown.copy(album = " "), HeaderYears()) {
            compose.onNodeWithText(app.getString(R.string.home_album_none)).assertExists()
        }
}
