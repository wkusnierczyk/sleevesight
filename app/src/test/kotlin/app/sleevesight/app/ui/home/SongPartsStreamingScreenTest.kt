package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SongParts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's part headings while its insight streams and once it is written (#1133): shown as headings
 * for the song's in two parts ([ItemEvidence.parts]) both times, and for any other song neither time,
 * so a reply that writes a heading-like line reads the same as it streams and once checked.
 */
@RunWith(RobolectricTestRunner::class)
class SongPartsStreamingScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val lines: EvidenceSet = EvidenceSet.of(listOf(draft(SourceId.WIKIPEDIA, "A line.")), JvmHasher)

    private val reply = "${SongParts.MUSIC}\n\nIt was composed in 1788 [W1]."

    private fun written(parts: Boolean): InsightState.Written {
        val checked = GroundingCheck.check(reply, lines.ids.toSet(), false, headings = parts)
        return InsightState.Written((checked as InsightReply.Written).grounded, cached = false)
    }

    // Whether the music's heading shows, while [reply] streams and once written, for a song in [parts].
    private fun headings(parts: Boolean): List<Boolean> {
        val track = HomeFixtures.track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done()))
        val notes = FakeItemNotes()
        val evidence = ItemEvidence(lines, emptyMap(), parts = parts)
        val shownAs = { state: InsightState ->
            notes.shown.value = mapOf(InsightItem.SONG to ItemShown.Shown(state, evidence, "Claude Haiku 5.5"))
        }
        shownAs(InsightState.Writing(reply, model = "Claude Haiku 5.5"))
        val seen = mutableListOf<Boolean>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)
                        val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                        val rows = itemRows(sundown, normalizer.normalize(sundown), null)
                        val folds = itemFolds(ItemFoldMemory(), sundown, NewTrackFold.FOLDED)
                        ItemRows(rows, false, folds, context) {}
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performClick()
            compose.waitForIdle()
            seen += shows()
            shownAs(written(parts))
            compose.waitForIdle()
            seen += shows()
        }
        return seen
    }

    private fun shows(): Boolean =
        compose.onAllNodesWithTag(songPartTag(SongParts.MUSIC)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a song in two parts shows its heading while it streams and once written`() {
        assertEquals(listOf(true, true), headings(parts = true))
    }

    @Test
    fun `any other song shows no heading, while it streams or once written`() {
        assertEquals(listOf(false, false), headings(parts = false))
    }
}
