package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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

private val DESCRIBED = SemanticsProperties.ContentDescription

/**
 * The track's header (#438): the title, the artists, and the album alone, under it the record label
 * of the release played and the album's year (#556, #729), read with the album as one sentence; with
 * no album reported, a line that says so, the first release being in the *Dates* panel (#724).
 */
@RunWith(RobolectricTestRunner::class)
class KeyDataTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    @Suppress("LongParameterList") // The track, its years, roles and labels, and what to check.
    private fun show(
        playing: NowPlaying,
        years: HeaderYears,
        split: Boolean = false,
        labels: AlbumLabels = AlbumLabels.Known(),
        labelHeld: Boolean = false,
        block: () -> Unit,
    ) {
        val normalized = normalizer.normalize(playing)
        val roles = splitOf(null, normalized).takeIf { split }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { KeyData(normalized, playing, years, roles, labels = labels, labelHeld = labelHeld) }
            }
            block()
        }
    }

    @Test
    fun withTheComposerToldThePerformersAreOneLineAndTheComposerTheNextAnArrangerInTheTitleAlone() {
        // `Bach: … (Arr. Busoni)` by Bach, Busoni and Grimaud (#1199): Grimaud alone performs.
        val chaconne =
            sundown.copy(
                title = "Bach: Partita No. 2 in D Minor, BWV 1004: V. Chaconne (Arr. Busoni)",
                artists = "Johann Sebastian Bach, Ferruccio Busoni, Hélène Grimaud",
            )
        show(chaconne, HeaderYears(), split = true) {
            compose.onNodeWithText("Hélène Grimaud").assertExists()
            compose.onNodeWithText("Ferruccio Busoni", substring = true).assertDoesNotExist()
            compose.onNodeWithTag(KEY_AUTHORS_TAG).assertTextEquals("Composer: Johann Sebastian Bach")
        }
    }

    private val gold = sundown.copy(album = "Gord's Gold")

    /** The words of [line]'s text in italics. */
    private fun italics(line: String): List<String> {
        val text =
            compose
                .onNodeWithText(line, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        return text.spanStyles
            .filter { it.item.fontStyle == FontStyle.Italic }
            .map { text.text.substring(it.start, it.end) }
    }

    // The line under the album (#729): its text, and what a screen reader reads for the album and it.
    private fun releaseLine(): String =
        compose.onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true).fetchSemanticsNode().text()

    private fun said(album: String): String =
        compose
            .onNodeWithText(album)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    private fun SemanticsNode.text(): String = config[SemanticsProperties.Text].joinToString { it.text }

    @Test
    fun theAlbumStandsAloneInItalicsAndItsYearIsOnTheLineUnderIt() =
        show(gold, HeaderYears(album = "1974")) {
            assertEquals(listOf("Gord's Gold"), italics("Gord's Gold"))
            compose.onNodeWithText("Gord's Gold, 1974").assertDoesNotExist()
            assertEquals("1974", releaseLine())
            // One stop with the album, read as a sentence.
            assertEquals("Gord's Gold, released in 1974.", said("Gord's Gold"))
            assertEquals(0, compose.onAllNodesWithText("Released", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun theReleasesLabelThenTheYearUnderTheAlbum() =
        show(gold, HeaderYears(album = "1975"), labels = AlbumLabels.Known(listOf("Reprise"), "1975")) {
            assertEquals("Reprise · 1975", releaseLine())
            assertEquals("Gord's Gold, released by Reprise in 1975.", said("Gord's Gold"))
        }

    @Test
    fun severalLabelsAreAllShownJoinedByCommasAndSaidTogether() =
        show(
            sundown.copy(album = "The Dark Side of the Moon"),
            HeaderYears(album = "1973"),
            labels = AlbumLabels.Known(listOf("Harvest", "Capitol Records"), "1973"),
        ) {
            assertEquals("Harvest, Capitol Records · 1973", releaseLine())
            assertEquals(
                "The Dark Side of the Moon, released by Harvest and Capitol Records in 1973.",
                said("The Dark Side of the Moon"),
            )
        }

    @Test
    fun withTheLabelAloneTheLineHoldsIt() =
        show(gold, HeaderYears(), labels = AlbumLabels.Known(listOf("Reprise"))) {
            assertEquals("Reprise", releaseLine())
            assertEquals("Gord's Gold, released by Reprise.", said("Gord's Gold"))
        }

    @Test
    fun whileTheLabelIsHeldBackAnEmptyLineKeepsItsHeightAndTheYearWaits() =
        show(gold, HeaderYears(album = "1975"), labels = AlbumLabels.Looking, labelHeld = true) {
            compose.onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("1975", substring = true).assertDoesNotExist()
            val held = compose.onNodeWithTag(RELEASE_HELD_TAG, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(true, held.size.height > 0)
            assertEquals(
                false,
                compose
                    .onNodeWithText("Gord's Gold")
                    .fetchSemanticsNode()
                    .config
                    .contains(DESCRIBED),
            )
        }

    @Test
    fun withNoYearAndNoLabelTheAlbumStandsAloneWithNoLineUnderIt() =
        show(gold, HeaderYears()) {
            assertEquals(listOf("Gord's Gold"), italics("Gord's Gold"))
            compose.onNodeWithTag(RELEASE_LINE_TAG, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(RELEASE_HELD_TAG, useUnmergedTree = true).assertDoesNotExist()
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
