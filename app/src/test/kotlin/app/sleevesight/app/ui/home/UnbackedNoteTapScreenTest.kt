package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SearchAnswer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A `?` chip after dimmed text goes to the note under its reply (#954), on every surface that shows
 * one: the key insight (with *Show grounding details* on too, where the note stays above the counts),
 * an item's insight, a genre's reason and *Explain the song*. Each is drawn in a short scrolling view
 * with a reply long enough that the note starts out of view; a tap on the chip as drawn, and the
 * paragraph's accessibility action, each bring the note into view and flash it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
// Real text layout, so a long reply wraps and the note starts out of view.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnbackedNoteTapScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // A dimmed sentence first, then enough backed ones to push the note out of the short view.
    private val reply = "It is great. " + List(BACKED) { "Sundown was recorded in 1973 [MB1]." }.joinToString(" ")

    private fun show(
        content: @Composable () -> Unit,
        before: () -> Unit = {},
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        Box(Modifier.height(VIEW.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                        }
                    }
                }
            }
            compose.waitForIdle()
            before()
            block()
        }
    }

    private fun prose(): SemanticsNodeInteraction =
        compose.onNode(
            hasText("It is great", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    private fun note() = compose.onNodeWithTag(UNBACKED_NOTE_TAG, useUnmergedTree = true)

    // The note, out of view, is brought into view and flashes once [go] has run.
    private fun assertGoesToTheNote(go: () -> Unit) {
        note().assertIsNotDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            go()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(INTO_VIEW_MILLIS)
            compose.waitForIdle()
            compose.onNodeWithTag(UNBACKED_NOTE_FLASHING_TAG, useUnmergedTree = true).assertIsDisplayed()
            compose
                .onNodeWithText(app.getString(R.string.home_note_unbacked), useUnmergedTree = true)
                .assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    // A tap on the `?` chip as drawn.
    private fun tapTheChip() =
        assertGoesToTheNote {
            prose().performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == UNBACKED_LINK_TAG }
        }

    // The paragraph's action that goes to the note, as a screen reader runs it.
    private fun runTheAction() =
        assertGoesToTheNote {
            val see = app.getString(R.string.home_cite_see_note)
            val action =
                prose()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single { it.label == see }
            compose.runOnUiThread { action.action() }
        }

    // The key insight's card, with [details] (*Show grounding details*) or not.
    private fun keyInsight(
        details: Boolean,
        block: () -> Unit,
    ) {
        val insight = HomeFixtures.written(reply)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = InsightOffers(),
            )
        val actions = InsightActions({}, {}, {}, onReport = { _, _ -> })
        show({ InsightCard(insight, context, actions, groundingDetails = details) }, block = block)
    }

    @Test
    fun theKeyInsightsChipGoesToItsNote() = keyInsight(details = false) { tapTheChip() }

    @Test
    fun theKeyInsightsActionGoesToItsNote() = keyInsight(details = false) { runTheAction() }

    @Test
    fun withGroundingDetailsTheKeyInsightsChipGoesToItsNoteAboveTheCounts() =
        keyInsight(details = true) { tapTheChip() }

    @Test
    fun withGroundingDetailsTheKeyInsightsActionGoesToItsNote() = keyInsight(details = true) { runTheAction() }

    // The song's insight, its row opened.
    private fun itemInsight(block: () -> Unit) {
        val track = HomeFixtures.track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done()))
        val notes = FakeItemNotes()
        val written = HomeFixtures.written(reply)
        val evidence = ItemEvidence(HomeFixtures.evidence, emptyMap())
        notes.shown.value = mapOf(InsightItem.SONG to ItemShown.Shown(written, evidence, "Claude Haiku 5.5"))
        val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show(
            {
                val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                val rows = itemRows(sundown, normalizer.normalize(sundown), null)
                val folds = itemFolds(ItemFoldMemory(), sundown, NewTrackFold.FOLDED)
                ItemRows(rows, false, folds, context) {}
            },
            before = {
                compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performClick()
                compose.waitForIdle()
            },
            block = block,
        )
    }

    @Test
    fun anItemInsightsChipGoesToItsNote() = itemInsight { tapTheChip() }

    @Test
    fun anItemInsightsActionGoesToItsNote() = itemInsight { runTheAction() }

    // A genre's reason, as written.
    private fun genreReason(block: () -> Unit) {
        val context =
            InsightContext(
                HomeFixtures.track(),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = InsightOffers(),
            )
        val actions = ReasonActions({}, null, {}, {}, {})
        val shown = GenreReason.Shown(HomeFixtures.written(reply))
        show({ ReasonShown(shown, "Why is this Rock?", context, actions) }, block = block)
    }

    @Test
    fun aGenreReasonsChipGoesToItsNote() = genreReason { tapTheChip() }

    @Test
    fun aGenreReasonsActionGoesToItsNote() = genreReason { runTheAction() }

    // *Explain the song*'s answer, citing a page.
    private fun explanation(block: () -> Unit) {
        val text = reply.replace("[MB1]", "[S1]")
        val grounded = (GroundingCheck.check(text, setOf("S1"), allowModel = false) as InsightReply.Written).grounded
        val page = CitedPage("S1", "https://example.net/sundown", "Sundown")
        val answer = SearchAnswer.Explanation(grounded, listOf(page))
        show({ AiExplanation(answer) }, block = block)
    }

    @Test
    fun theExplanationsChipGoesToItsNote() = explanation { tapTheChip() }

    @Test
    fun theExplanationsActionGoesToItsNote() = explanation { runTheAction() }

    private companion object {
        const val VIEW = 240
        const val BACKED = 24
        const val INTO_VIEW_MILLIS = 600L
    }
}
