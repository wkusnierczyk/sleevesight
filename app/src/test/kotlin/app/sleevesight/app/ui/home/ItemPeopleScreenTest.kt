package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The other performers' chips in the artist's panel (#1199): one artist's insight open at a time,
 * each a tap's write; the focus goes to the open one's *Close*, a chip tapped while another is open
 * included, and back to its chip when it closes.
 */
@RunWith(RobolectricTestRunner::class)
class ItemPeopleScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val playing =
        sundown.copy(
            title = "Nessun Dorma",
            artists = "$PUCCINI, $PAVAROTTI, $MEHTA, $LA_PHIL",
            mediaId = "spotify:track:nessun",
        )

    private val track =
        HomeFixtures
            .track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(
                resolution =
                    Resolution(
                        recordingMbid = "r",
                        roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL)),
                    ),
            )

    private fun close(name: String) = itemPanelTag(InsightItem.named(name)) + ":close"

    @Test
    fun aChipTappedWhileAnotherIsOpenTakesTheFocusToItsCloseAndClosingGoesBackToItsChip() {
        val notes = FakeItemNotes()
        val normalized = normalizer.normalize(playing)
        val rows = itemRows(playing, normalized, splitOf(track, normalized))
        val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                    val folds = itemFolds(ItemFoldMemory(), playing, NewTrackFold.FOLDED)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ItemRows(rows, false, folds, context) {}
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemChipTag(MEHTA)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(close(MEHTA)).assertIsFocused()
            // Another chip while one is open: its panel in the other's place, the focus on its *Close*.
            compose.onNodeWithTag(itemChipTag(LA_PHIL)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(close(MEHTA)).assertDoesNotExist()
            compose.onNodeWithTag(close(LA_PHIL)).assertIsFocused()
            compose.onNodeWithTag(close(LA_PHIL)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(close(LA_PHIL)).assertDoesNotExist()
            compose.onNodeWithTag(itemChipTag(LA_PHIL)).assertIsFocused()
            // Each opened by its tap, its write asked for once.
            assertEquals(
                listOf(InsightItem.named(MEHTA), InsightItem.named(LA_PHIL)),
                notes.writes.filter { it.artist != null },
            )
        }
    }

    private companion object {
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val LA_PHIL = "Los Angeles Philharmonic"
    }
}
