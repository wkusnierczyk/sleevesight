package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The legend of *Sources*' chips (#951): closed each time *Sources* opens, one line in the table's
 * columns (a **?** pill under the chips, *Explain the source chips* under the names), a button; a
 * tap opens it in that line's place, its heading a button that closes it, a row for each look of
 * the chips, read as text, and how to use them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesLegendScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val madryt: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
                    SourceId.LASTFM to
                        done(
                            Outcome.ArtistArticle,
                            unverified = listOf(draft(SourceId.LASTFM, "Kult is a band.", scope = Scope.ARTIST)),
                        ),
                ),
            evidence = EvidenceSet.of(listOf(musicBrainzLine), JvmHasher),
        )

    private val rows =
        listOf(
            R.string.home_sources_legend_verified,
            R.string.home_sources_legend_unverified,
            R.string.home_sources_legend_hidden,
            R.string.home_sources_legend_none,
        )

    private fun show(
        dark: Boolean = false,
        fontScale: Float = 1f,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(madryt, aiReady = true),
                madryt.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        val actions = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            // On the theme's surface, as the app draws it, so the dark pictures are dark.
                            Column(
                                Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                                    .verticalScroll(rememberScrollState()),
                            ) { NowPlayingContent(state, actions) }
                        }
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun unfold() = tap(SOURCES_CHIP_TAG)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun countText(text: String) =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun inLegend(text: String) =
        compose
            .onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(SOURCES_LEGEND_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun closed() {
        assertEquals(1, count(SOURCES_LEGEND_ASK_TAG))
        assertEquals(0, count(SOURCES_LEGEND_HEADING_TAG))
        for (row in rows) assertEquals(text(row), 0, countText(text(row)))
        assertEquals(0, countText(text(R.string.home_sources_legend_tap)))
    }

    private fun open() {
        assertEquals(0, count(SOURCES_LEGEND_ASK_TAG))
        assertEquals(1, count(SOURCES_LEGEND_HEADING_TAG))
        for (row in rows) assertEquals(text(row), 1, inLegend(text(row)))
        assertEquals(1, inLegend(text(R.string.home_sources_legend_tap)))
    }

    private fun button(
        tag: String,
        name: Int,
        state: Int,
    ) = compose
        .onNodeWithTag(tag)
        .assertTextEquals(text(name))
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(state)))
        .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    @Test
    fun theLegendIsClosedUntilTappedAndItsHeadingClosesItAgain() =
        show {
            assertEquals(0, count(SOURCES_LEGEND_TAG))
            unfold()
            closed()
            // Its words as picked.
            assertEquals("Explain the source chips", text(R.string.home_sources_legend_explain))
            assertEquals("Source chips", text(R.string.home_sources_legend_title))
            assertEquals(
                listOf(
                    "Verified, cited in the insight",
                    "Unverified, not cited in the insight",
                    "Hidden, tap to show",
                    "No results from this source",
                ),
                rows.map(::text),
            )
            assertEquals("Tap a source to show or hide its results.", text(R.string.home_sources_legend_tap))
            tap(SOURCES_LEGEND_ASK_TAG)
            open()
            tap(SOURCES_LEGEND_HEADING_TAG)
            closed()
        }

    @Test
    fun thePillAndTheTextEachOpenTheLegend() =
        show {
            unfold()
            // The pill, under the chips, on the screen.
            compose.onNodeWithTag(SOURCES_LEGEND_ASK_TAG).performScrollTo()
            compose.onNodeWithTag(SOURCES_LEGEND_PILL_TAG, useUnmergedTree = true).performClick()
            compose.waitForIdle()
            open()
            tap(SOURCES_LEGEND_HEADING_TAG)
            compose.onNodeWithTag(SOURCES_LEGEND_ASK_TAG).performScrollTo()
            compose.onNodeWithText(text(R.string.home_sources_legend_explain), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            open()
        }

    @Test
    fun theLegendIsClosedAgainEachTimeSourcesOpens() =
        show {
            unfold()
            tap(SOURCES_LEGEND_ASK_TAG)
            open()
            // Folded and opened again.
            tap(SOURCES_PANEL_CLOSE_TAG)
            assertEquals(0, count(SOURCES_LEGEND_TAG))
            unfold()
            closed()
        }

    @Test
    fun theLegendSitsInTheTablesColumnsUnderItBeforeTheNote() =
        show {
            unfold()
            val chip = compose.onNodeWithTag(CHIP_PREFIX + "MB").fetchSemanticsNode()
            val pill =
                compose.onNodeWithTag(SOURCES_LEGEND_PILL_TAG, useUnmergedTree = true).fetchSemanticsNode()
            // What Last.fm's row found, under its name, where the names' column starts.
            val name = compose.onNodeWithTag(STATUS_PREFIX + "L", useUnmergedTree = true)
            val explain =
                compose
                    .onNodeWithText(text(R.string.home_sources_legend_explain), useUnmergedTree = true)
                    .fetchSemanticsNode()
            // The pill right-aligned with the chips; the words aligned with the names.
            val chipEnd = chip.boundsInRoot.right
            val pillEnd = pill.boundsInRoot.right
            assertTrue("pill ends at $pillEnd, chips at $chipEnd", abs(pillEnd - chipEnd) < 2f)
            val nameStart = name.fetchSemanticsNode().boundsInRoot.left
            assertEquals(nameStart, explain.boundsInRoot.left, 2f)
            // Under the table.
            val table = compose.onNodeWithTag(SOURCES_TABLE_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val legend = compose.onNodeWithTag(SOURCES_LEGEND_TAG, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(legend.boundsInRoot.top >= table.boundsInRoot.bottom)
            // Open: the texts aligned with the names, the heading with the table's start.
            tap(SOURCES_LEGEND_ASK_TAG)
            for (row in rows + R.string.home_sources_legend_tap) {
                val at =
                    compose
                        .onNodeWithText(text(row), useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot.left
                assertEquals(text(row), nameStart, at, 2f)
            }
        }

    @Test
    fun aScreenReaderHearsTheClosedLineAsACollapsedButtonAndTheHeadingAsAnExpandedOne() =
        show {
            unfold()
            button(SOURCES_LEGEND_ASK_TAG, R.string.home_sources_legend_explain, R.string.home_fold_collapsed)
            // One node, read by its words alone (the pill not read): not apart.
            val explain = compose.onAllNodesWithText(text(R.string.home_sources_legend_explain))
            assertEquals(1, explain.fetchSemanticsNodes().size)
            assertEquals(
                0,
                compose.onAllNodesWithText(text(R.string.home_sources_legend_pill)).fetchSemanticsNodes().size,
            )
            compose.onNodeWithTag(SOURCES_LEGEND_ASK_TAG).performScrollTo().assertTouchHeightIsEqualTo(48.dp)
            tap(SOURCES_LEGEND_ASK_TAG)
            button(SOURCES_LEGEND_HEADING_TAG, R.string.home_sources_legend_title, R.string.home_fold_expanded)
            // The rows read as text, in the merged tree a screen reader is given; no sample read.
            for (row in rows + R.string.home_sources_legend_tap) {
                val read =
                    compose
                        .onAllNodes(hasText(text(row)))
                        .fetchSemanticsNodes()
                        .filterNot { SemanticsProperties.HideFromAccessibility in it.config }
                assertEquals(text(row), 1, read.size)
            }
            val legend =
                compose
                    .onAllNodes(hasAnyAncestor(hasTestTag(SOURCES_LEGEND_TAG)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
            val readable =
                legend.filter {
                    SemanticsProperties.HideFromAccessibility !in it.config &&
                        SemanticsProperties.Text in it.config
                }
            val read = readable.map { it.config[SemanticsProperties.Text] }
            assertEquals("$read", 1 + rows.size + 1, readable.size)
            // No button in it but its heading.
            val buttons =
                legend.filter {
                    SemanticsActions.OnClick in it.config &&
                        it.config.getOrNull(SemanticsProperties.TestTag) != SOURCES_LEGEND_HEADING_TAG
                }
            assertFalse("$buttons", buttons.isNotEmpty())
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theLegendClosedAndOpenLightDarkAndInALargeFont() {
        for ((dark, font) in listOf(false to 1f, true to 1f, false to 2f)) {
            val name =
                when {
                    font > 1f -> "font-200"
                    dark -> "dark"
                    else -> "light"
                }
            show(dark = dark, fontScale = font) {
                unfold()
                keep("legend-closed-$name")
                tap(SOURCES_LEGEND_ASK_TAG)
                open()
                keep("legend-open-$name")
                // Each text on the screen, none cut: the tap line ends inside the legend.
                val legend = compose.onNodeWithTag(SOURCES_LEGEND_TAG, useUnmergedTree = true).fetchSemanticsNode()
                val tapLine =
                    compose
                        .onNodeWithText(text(R.string.home_sources_legend_tap), useUnmergedTree = true)
                        .fetchSemanticsNode()
                assertTrue(tapLine.boundsInRoot.bottom <= legend.boundsInRoot.bottom + 1f)
                assertTrue(tapLine.boundsInRoot.right <= legend.boundsInRoot.right + 1f)
            }
        }
    }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // The screen as drawn, scrolled to the legend, kept for a look when SLEEVESIGHT_PICTURES names a folder.
    private fun keep(name: String) {
        val dir = out ?: return
        compose.onNodeWithTag(SOURCES_LEGEND_TAG).performScrollTo()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
