package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The other performers' chips in the artist's panel (#1199) and on its page: a chip's tap puts its
 * artist's insight in the chip's place, titled with the name, and *Close* puts the chip back
 * (#1288); each opens and closes on its own, several at once, each a tap's write; the focus goes to
 * the opened panel's *Close*, and back to its chip when it closes.
 */
@RunWith(RobolectricTestRunner::class)
class ItemPeopleScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val playing =
        sundown.copy(
            title = "Nessun Dorma",
            artists = "$PUCCINI, $PAVAROTTI, $MEHTA, $LA_PHIL, $DOMINGO",
            mediaId = "spotify:track:nessun",
        )

    private val track =
        HomeFixtures
            .track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(
                resolution =
                    Resolution(
                        recordingMbid = "r",
                        roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL, DOMINGO)),
                    ),
            )

    private fun state(said: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, said)

    private fun panel(name: String) = itemPanelTag(InsightItem.named(name))

    private fun close(name: String) = panel(name) + ":close"

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // Home under *Expandable boxes*, the artist's row unfolded; or, with [page], the artist's page.
    private fun show(
        notes: FakeItemNotes,
        page: Boolean,
    ) = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
        val normalized = normalizer.normalize(playing)
        scenario.onActivity {
            it.setContent {
                val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (page) {
                        val row = itemRow(InsightItem.ARTIST, playing, normalized, track)
                        ItemPage(InsightItem.ARTIST, row, context, tapped = true)
                    } else {
                        val rows = itemRows(playing, normalized, splitOf(track, normalized))
                        val folds = itemFolds(ItemFoldMemory(), playing, NewTrackFold.FOLDED)
                        ItemRows(rows, false, folds, context) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        if (!page) {
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    // The other performers, each its chip or, when [open], its panel, in the track's order: down the
    // panel, chips on one line from left to right.
    private fun assertInOrder(vararg open: String) {
        val bounds =
            listOf(MEHTA, LA_PHIL, DOMINGO).map { name ->
                val tag = if (name in open) panel(name) else itemChipTag(name)
                compose.onNodeWithTag(tag).assertExists()
                compose.onNodeWithTag(if (name in open) itemChipTag(name) else panel(name)).assertDoesNotExist()
                compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue("$a before $b", a.bottom <= b.top || (a.top == b.top && a.right <= b.left))
        }
    }

    @Test
    fun anOpenPanelKeepsItsStateWhileOthersOpenAndCloseBeforeIt() {
        // A refused write offers it again: a panel rebuilt would ask for it again without a tap.
        val notes = FakeItemNotes().apply { takenUp = false }
        show(notes, page = false).use {
            compose.onNodeWithTag(itemChipTag(DOMINGO)).performScrollTo().performClick()
            compose.waitForIdle()
            assertInOrder(DOMINGO)
            compose.onNodeWithTag(itemChipTag(MEHTA)).performScrollTo().performClick()
            compose.waitForIdle()
            assertInOrder(MEHTA, DOMINGO)
            compose.onNodeWithTag(close(MEHTA)).performScrollTo().performClick()
            compose.waitForIdle()
            assertInOrder(DOMINGO)
            // The last one's panel kept all along: followed once, its write asked for once.
            assertEquals(1, notes.followed.count { it == InsightItem.named(DOMINGO) })
            assertEquals(1, notes.writes.count { it == InsightItem.named(DOMINGO) })
            assertEquals(1, notes.writes.count { it == InsightItem.named(MEHTA) })
        }
    }

    @Test
    fun aChipGivesWayToItsPanelAndCloseBringsItBackUnderBoxes() = chipsGiveWayToPanels(page = false)

    @Test
    fun aChipGivesWayToItsPanelAndCloseBringsItBackOnThePage() = chipsGiveWayToPanels(page = true)

    private fun chipsGiveWayToPanels(page: Boolean) {
        val notes = FakeItemNotes()
        show(notes, page).use {
            // Nothing about the other performers is written before their chip's tap.
            assertEquals(emptyList<InsightItem>(), notes.writes.filter { it.artist != null })
            compose.onNodeWithTag(itemChipTag(MEHTA)).assert(state("collapsed"))
            compose
                .onNodeWithTag(itemChipTag(MEHTA))
                .assert(SemanticsMatcher("shows") { it.config[SemanticsActions.OnClick].label == SHOW })
            compose.onNodeWithTag(itemChipTag(MEHTA)).performScrollTo().performClick()
            compose.waitForIdle()
            // The chip gives way to its panel, titled with the name, in the chip's place: above the
            // next chip, not under the chips.
            compose.onNodeWithTag(itemChipTag(MEHTA)).assertDoesNotExist()
            compose.onNode(hasText(MEHTA) and hasAnyAncestor(hasTestTag(panel(MEHTA)))).assertExists()
            assertTrue(top(panel(MEHTA)) < top(itemChipTag(LA_PHIL)))
            compose.onNodeWithTag(close(MEHTA)).assertIsFocused()
            // Another chip while one is open: both open, each in its own place, the focus on its *Close*.
            compose.onNodeWithTag(itemChipTag(LA_PHIL)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemChipTag(LA_PHIL)).assertDoesNotExist()
            compose.onNodeWithTag(panel(MEHTA)).assertExists()
            assertTrue(top(panel(MEHTA)) < top(panel(LA_PHIL)))
            compose.onNodeWithTag(close(LA_PHIL)).assertIsFocused()
            // *Close* puts the chip back where it was, and the focus on it; the other stays open.
            compose.onNodeWithTag(close(MEHTA)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(panel(MEHTA)).assertDoesNotExist()
            compose.onNodeWithTag(itemChipTag(MEHTA)).assertIsFocused()
            assertTrue(top(itemChipTag(MEHTA)) < top(panel(LA_PHIL)))
            compose.onNodeWithTag(close(LA_PHIL)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(panel(LA_PHIL)).assertDoesNotExist()
            compose.onNodeWithTag(itemChipTag(LA_PHIL)).assertIsFocused()
            compose.onNodeWithTag(itemChipTag(MEHTA)).assertExists()
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
        const val DOMINGO = "Plácido Domingo"
        const val SHOW = "show this artist’s insight"
    }
}
