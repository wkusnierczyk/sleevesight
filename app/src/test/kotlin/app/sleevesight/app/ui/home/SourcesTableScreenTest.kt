package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
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
import app.sleevesight.app.ui.home.HomeFixtures.wikidataLine
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * *Sources*' table (#662): a row for each source that is on, its letter as a chip, its name and
 * what it found; the chip filled while its lines show, outlined while hidden, dimmed with none; the
 * rows close; a note only for a key or a failure (#440); and under *Show grounding details* the
 * sources that are off, the statuses that are diagnostics and the rejected matches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesTableScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private var settingsOpened = 0

    // The model's own knowledge allowed, so there is an `AI` row.
    private val modelOn = Settings(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)
    private val details = modelOn.copy(showGroundingDetails = true)

    /**
     * Sundown's lines from MusicBrainz (kept from an earlier lookup) and Wikipedia; web search's
     * match rejected, Discogs off, and Last.fm's key not saved.
     */
    private val mixed: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine), cached = true),
                    SourceId.WIKIPEDIA to done(evidence = listOf(wikipediaLine, wikidataLine)),
                    SourceId.WEB to done(Outcome.Rejected("another song")),
                    SourceId.DISCOGS to done(Outcome.SourceOff),
                ),
        )

    private fun show(
        track: TrackState,
        settings: Settings = modelOn,
        needsKey: Set<SourceId> = setOf(SourceId.LASTFM),
        fontScale: Float = 1f,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true, needsKey = needsKey),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
            )
        val actions = NowPlayingActions(normalizer::normalize, openSettings = { settingsOpened++ })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun unfold() {
        compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun countText(
        text: String,
        substring: Boolean = false,
    ) = compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

    /** The rows of the table, by their sources' tags, top to bottom. */
    private fun rows(): List<String> =
        compose
            .onAllNodes(SemanticsMatcher("a row") { it.tag()?.startsWith(ROW_PREFIX) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .map { it.tag()!!.removePrefix(ROW_PREFIX) }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    /** What the row of [tag] says in words a screen reader reads as text, in order. */
    private fun said(tag: String): List<String> {
        val words = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.let { texts -> words += texts.map { it.text } }
            node.children.forEach(::walk)
        }
        // The merged tree, as a screen reader is given it: what a node clears is not in it.
        walk(compose.onNodeWithTag(ROW_PREFIX + tag).fetchSemanticsNode())
        return words
    }

    private fun switch(
        tag: String,
        name: String,
        shown: Boolean,
    ) = compose
        .onNodeWithTag(CHIP_PREFIX + tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(shown)))
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                text(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden),
            ),
        )

    @Test
    fun theTableHasARowForEachSourceThatIsOnWithWhatItFoundAndNoDiagnostics() =
        show(mixed) {
            unfold()
            // Off (Discogs) or without its key (Last.fm): no row. Rejected (web search) and the
            // model's own knowledge: a row.
            assertEquals(listOf("MB", "W", "G", "AI"), rows())
            assertEquals(listOf(text(R.string.home_source_found_song)), said("MB"))
            assertEquals(listOf(text(R.string.home_source_found_song)), said("W"))
            // A dimmed chip's row says its name; a rejected match is nothing found.
            assertEquals(listOf(text(R.string.home_source_name_web), text(R.string.home_source_none)), said("G"))
            assertEquals(
                listOf(text(R.string.settings_source_model), text(R.string.home_source_model_allowed)),
                said("AI"),
            )
            // No diagnostics: kept, rejected, off.
            assertEquals(0, countText(text(R.string.home_source_cached, ""), substring = true))
            assertEquals(0, countText(text(R.string.home_source_rejected)))
            assertEquals(0, countText(text(R.string.home_source_off)))
            assertEquals(0, countText(text(R.string.home_rejected_title)))
        }

    @Test
    fun aChipWithLinesIsASwitchNamedByItsSourceAndADimmedOneIsNoButton() =
        show(mixed) {
            unfold()
            switch("MB", text(R.string.settings_source_musicbrainz), shown = true)
            switch("W", text(R.string.home_source_chip_wikipedia), shown = true)
            assertEquals(
                text(R.string.home_source_filter, text(R.string.home_source_chip_wikipedia)),
                compose
                    .onNodeWithTag(CHIP_PREFIX + "W")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .label,
            )
            for (tag in listOf("G", "AI")) {
                val chip = compose.onNodeWithTag(CHIP_PREFIX + tag).fetchSemanticsNode().config
                assertFalse(tag, SemanticsActions.OnClick in chip)
                assertFalse(tag, SemanticsProperties.Role in chip)
                assertFalse(tag, SemanticsProperties.ToggleableState in chip)
                // Nor an empty node a screen reader would stop on: no node of the chip merges.
                val merging =
                    compose
                        .onAllNodes(hasTestTag(CHIP_PREFIX + tag) or hasAnyAncestor(hasTestTag(CHIP_PREFIX + tag)))
                        .fetchSemanticsNodes()
                        .filter { it.config.isMergingSemanticsOfDescendants }
                assertEquals(tag, emptyList<SemanticsNode>(), merging)
            }
            // A switch is one node, as before.
            assertTrue(
                compose
                    .onNodeWithTag(CHIP_PREFIX + "W")
                    .fetchSemanticsNode()
                    .config.isMergingSemanticsOfDescendants,
            )
            // A dimmed chip's row is read as one text: the source's name and what it found.
            val web = text(R.string.home_source_name_web)
            compose
                .onNode(hasText(web) and hasText(text(R.string.home_source_none)))
                .assertTextEquals(web, text(R.string.home_source_none))
            // A switch says its source's name: the name beside it is not read again.
            val musicBrainz = text(R.string.settings_source_musicbrainz)
            assertEquals(0, compose.onAllNodesWithText(musicBrainz).fetchSemanticsNodes().size)
            // Each chip answers a touch across 48 dp, though it lays out less.
            compose
                .onNodeWithTag(CHIP_PREFIX + "W")
                .assertTouchHeightIsEqualTo(48.dp)
                .assertTouchWidthIsEqualTo(48.dp)
        }

    @Test
    fun aTapHidesItsSourcesLinesAndAnotherShowsThemAgain() =
        show(mixed) {
            unfold()
            tap(CHIP_PREFIX + "W")
            switch("W", text(R.string.home_source_chip_wikipedia), shown = false)
            // Wikipedia's chip covers Wikidata's lines too.
            assertEquals(0, count(LINE_PREFIX + "W1"))
            assertEquals(0, count(LINE_PREFIX + "WD1"))
            assertEquals(1, count(LINE_PREFIX + "MB1"))
            tap(CHIP_PREFIX + "W")
            switch("W", text(R.string.home_source_chip_wikipedia), shown = true)
            assertEquals(1, count(LINE_PREFIX + "W1"))
            assertEquals(1, count(LINE_PREFIX + "WD1"))
        }

    // Native graphics, on the phone's width and density: the chips as drawn.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aChipIsFilledWhileItsLinesShowOutlinedWhileHiddenAndDimmedWithNone() =
        show(mixed) {
            unfold()
            tap(CHIP_PREFIX + "W")
            val shown = picture(CHIP_PREFIX + "MB")
            val hidden = picture(CHIP_PREFIX + "W")
            val dimmed = picture(CHIP_PREFIX + "G")
            assertTrue("a shown chip is filled: ${hex(fillOf(shown))}", far(fillOf(shown), groundOf(shown)))
            assertTrue("a hidden chip is not: ${hex(fillOf(hidden))}", near(fillOf(hidden), groundOf(hidden)))
            assertTrue("a dimmed chip is not: ${hex(fillOf(dimmed))}", near(fillOf(dimmed), groundOf(dimmed)))
            assertTrue("a hidden chip is outlined: ${hex(edgeOf(hidden))}", far(edgeOf(hidden), groundOf(hidden)))
            // Dimmed: an outline fainter than a hidden chip's.
            assertTrue(
                "a dimmed chip's outline ${hex(edgeOf(dimmed))} is fainter than ${hex(edgeOf(hidden))}",
                distance(edgeOf(dimmed), groundOf(dimmed)) < distance(edgeOf(hidden), groundOf(hidden)),
            )
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theRowsSitCloseWithTheChipsRightAlignedInTheirColumn() =
        show(mixed) {
            unfold()
            val rows =
                rows().map { tag ->
                    compose.onNodeWithTag(ROW_PREFIX + tag, useUnmergedTree = true).fetchSemanticsNode().let {
                        it.positionInRoot.y to it.positionInRoot.y + it.size.height
                    }
                }
            val gaps = rows.zipWithNext { above, below -> (below.first - above.second) / density() }
            assertTrue("rows about 6 dp apart: $gaps", gaps.all { abs(it - ROW_GAP_DP) < 1f })
            val chips =
                listOf("MB", "W", "G", "AI").map { compose.onNodeWithTag(CHIP_PREFIX + it).fetchSemanticsNode() }
            val ends = chips.map { it.positionInRoot.x + it.size.width }
            assertTrue("one right edge: $ends", ends.maxOf { it } - ends.minOf { it } < 1f)
            // A chip lays out its own height, not its 48 dp target.
            for (chip in chips) assertTrue("${chip.size.height} px", chip.size.height / density() < TARGET_DP)
        }

    /** Touches the screen at [dp] from [chip]'s top (negative above it), at its horizontal centre. */
    private fun touch(
        chip: String,
        dp: Float,
    ) {
        val bounds = compose.onNodeWithTag(CHIP_PREFIX + chip).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(bounds.center.x, bounds.top + dp * density())) }
        compose.waitForIdle()
    }

    private fun shown(tag: String) =
        compose.onNodeWithTag(CHIP_PREFIX + tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
            ToggleableState.On

    /**
     * Touches just outside a chip, above and below it, and between two rows, each nearer one chip:
     * the nearer chip toggles, and only it. At [fontScale].
     */
    private fun touchesGoToTheNearerChip(fontScale: Float) =
        show(mixed, fontScale = fontScale) {
            unfold()
            compose.onNodeWithTag(ROW_PREFIX + "G", useUnmergedTree = true).performScrollTo()
            val height =
                compose
                    .onNodeWithTag(CHIP_PREFIX + "MB")
                    .fetchSemanticsNode()
                    .size.height / density()
            val wikipedia =
                compose
                    .onNodeWithTag(CHIP_PREFIX + "W")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            val musicBrainz =
                compose
                    .onNodeWithTag(CHIP_PREFIX + "MB")
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom
            // Halfway between MusicBrainz's chip and Wikipedia's, in dp from MusicBrainz's top.
            val between = height + (wikipedia - musicBrainz) / density() / 2
            // How far beyond its edges a chip answers: up to its 48 dp, none once it is as tall.
            val reach = (TARGET_DP - height) / 2
            if (reach > NEAR) {
                // Above MusicBrainz's chip, within its 48 dp.
                touch("MB", -NEAR)
                assertEquals(false to true, shown("MB") to shown("W"))
                touch("MB", -NEAR)
                assertEquals(true to true, shown("MB") to shown("W"))
                // Below it, nearer it than Wikipedia's.
                touch("MB", between - NEAR)
                assertEquals(false to true, shown("MB") to shown("W"))
                touch("MB", between - NEAR)
                // Below that, nearer Wikipedia's.
                touch("MB", between + NEAR)
                assertEquals(true to false, shown("MB") to shown("W"))
                touch("MB", between + NEAR)
                assertEquals(true to true, shown("MB") to shown("W"))
                // Below Wikipedia's chip, nearer it than the dimmed web search's, which takes no touch.
                touch("W", height + NEAR)
                assertEquals(true to false, shown("MB") to shown("W"))
            } else {
                // A chip as tall as its target: a touch outside it, above or between two, toggles
                // nothing; one just inside its edge toggles it.
                touch("MB", -NEAR)
                touch("MB", between - NEAR.coerceAtMost(between - height - 1f))
                touch("MB", between + NEAR.coerceAtMost(between - height - 1f))
                assertEquals(true to true, shown("MB") to shown("W"))
                touch("MB", NEAR)
                assertEquals(false to true, shown("MB") to shown("W"))
                touch("W", NEAR)
                assertEquals(false to false, shown("MB") to shown("W"))
            }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTouchNearAChipGoesToTheNearerChip() = touchesGoToTheNearerChip(1f)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTouchNearAChipGoesToTheNearerChipInALargeFont() = touchesGoToTheNearerChip(2f)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theRowsAreAtLeastATouchAreaApartSoTheTargetsDoNotOverlap() =
        show(mixed) {
            unfold()
            // Unclipped: a chip scrolled out of view has empty bounds in the root.
            val centres =
                listOf("MB", "W", "G", "AI").map {
                    val chip = compose.onNodeWithTag(CHIP_PREFIX + it).fetchSemanticsNode()
                    chip.positionInRoot.y + chip.size.height / 2f
                }
            val pitches = centres.zipWithNext { above, below -> (below - above) / density() }
            assertTrue("chips 48 dp apart at the least: $pitches", pitches.all { it >= TARGET_DP - 0.5f })
        }

    @Test
    fun withGroundingDetailsAFailureOrARejectedMatchIsInTheErrorColour() {
        fun colour(tag: String): ComposeColor {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithTag(STATUS_PREFIX + tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            return layouts
                .single()
                .layoutInput.style.color
        }
        show(mixed, settings = details) {
            unfold()
            assertNotEquals(colour("MB"), colour("G"))
        }
        show(mixed) {
            unfold()
            assertEquals(colour("MB"), colour("G"))
        }
    }

    @Test
    fun thePlayersReleaseRowIsNamedPlayer() {
        val release = draft(SourceId.PLAYER, "On the release \"Sundown\".")
        val evidence = EvidenceSet.of(listOf(musicBrainzLine, release), JvmHasher)
        val sources = mapOf(SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)))
        show(track(sources = sources, evidence = evidence)) {
            unfold()
            assertEquals("P", rows().first())
            switch("P", text(R.string.home_source_chip_player), shown = true)
            assertEquals(listOf(text(R.string.home_source_release)), said("P"))
        }
    }

    @Test
    fun aSourceThatNeedsItsKeySaysSoWithALinkToTheSettings() =
        show(mixed) {
            // Folded: no note.
            assertEquals(0, count(SOURCES_NOTE_TAG))
            unfold()
            val lastFm = text(R.string.settings_source_lastfm)
            compose
                .onNodeWithTag(SOURCES_NOTE_TAG)
                .assertTextEquals(
                    text(R.string.home_source_note_key, lastFm, text(R.string.home_source_note_settings)),
                )
            settingsOpened = 0
            compose.onNodeWithTag(SOURCES_NOTE_TAG).performScrollTo().performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(1, settingsOpened)
        }

    @Test
    fun aSourceUnreachableOrFailedSaysSoAndNothingFoundRejectedOrOffSaysNothing() {
        val failing =
            track(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
                        SourceId.WIKIPEDIA to done(Outcome.Rejected("a film")),
                        SourceId.LASTFM to SourceState.Failed("IllegalStateException"),
                        SourceId.DISCOGS to done(Outcome.None),
                        SourceId.WEB to done(Outcome.Unreachable),
                    ),
            )
        show(failing, needsKey = emptySet()) {
            unfold()
            val failed = text(R.string.home_source_note_failed, text(R.string.settings_source_lastfm))
            val unreachable = text(R.string.home_source_note_unreachable, text(R.string.home_source_name_web))
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertTextEquals("$failed $unreachable")
            // The table says it too, row by row.
            assertEquals(
                listOf(text(R.string.settings_source_lastfm), text(R.string.home_source_failed)),
                said("L"),
            )
            assertEquals(
                listOf(text(R.string.home_source_name_web), text(R.string.home_source_unreachable)),
                said("G"),
            )
        }
        show(mixed, needsKey = emptySet()) {
            unfold()
            assertEquals(0, count(SOURCES_NOTE_TAG))
        }
    }

    @Test
    fun groundingDetailsAddTheSourcesThatAreOffTheDiagnosticsAndTheRejectedMatches() =
        show(mixed, settings = details) {
            unfold()
            // The same table, with the sources that are off: Discogs, and Last.fm without its key.
            assertEquals(listOf("MB", "W", "D", "G", "L", "AI"), rows())
            assertEquals(
                listOf(text(R.string.home_source_cached, text(R.string.home_source_found_song))),
                said("MB"),
            )
            assertEquals(listOf(text(R.string.home_source_name_web), text(R.string.home_source_rejected)), said("G"))
            assertEquals(listOf(text(R.string.home_source_name_discogs), text(R.string.home_source_off)), said("D"))
            assertEquals(
                listOf(text(R.string.settings_source_lastfm), text(R.string.home_source_needs_key)),
                said("L"),
            )
            // Still a switch where it has lines, dimmed where it has none.
            switch("MB", text(R.string.settings_source_musicbrainz), shown = true)
            val discogs = compose.onNodeWithTag(CHIP_PREFIX + "D").fetchSemanticsNode().config
            assertFalse(SemanticsActions.OnClick in discogs)
            compose.onNodeWithText(text(R.string.home_rejected_title)).performScrollTo().assertExists()
            val web = text(R.string.home_source_name_web)
            compose.onNodeWithText(text(R.string.home_rejected, web, "another song")).assertExists()
            // The note of the key stays, with its link.
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertExists()
        }

    @Test
    fun groundingDetailsStillShowNothingButTheChipWhileFolded() =
        show(mixed, settings = details) {
            assertEquals(0, count(SOURCES_TABLE_TAG))
            for (tag in listOf("MB", "W", "G", "L", "AI")) assertEquals(tag, 0, count(CHIP_PREFIX + tag))
            assertEquals(0, countText(text(R.string.home_rejected_title)))
            assertEquals(0, count(SOURCES_NOTE_TAG))
        }

    // The chip as drawn, its rounded shape and no more, scrolled into view.
    private fun picture(tag: String): Bitmap =
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .captureToImage()
            .asAndroidBitmap()

    private fun density() = app.resources.displayMetrics.density

    private fun px(dp: Float) = (dp * density()).toInt()

    // A chip's own colour, inside its rounded end at mid-height, past its outline: its fill.
    private fun fillOf(chip: Bitmap) = chip.getPixel(px(INSIDE), chip.height / 2)

    // What a chip is drawn on: its top left corner, outside its rounded end.
    private fun groundOf(chip: Bitmap) = chip.getPixel(1, 1)

    // A chip's left edge at mid-height: its outline.
    private fun edgeOf(chip: Bitmap) = chip.getPixel(1, chip.height / 2)

    private fun distance(
        a: Int,
        b: Int,
    ) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun near(
        a: Int,
        b: Int,
    ) = distance(a, b) <= TOLERANCE

    // Plainly another colour, as a fill or an outline is from what it is drawn on.
    private fun far(
        a: Int,
        b: Int,
    ) = distance(a, b) > DISTINCT

    private fun hex(colour: Int) = "#%08X".format(colour)

    private companion object {
        // Where a chip's fill is read, in dp from its left edge: inside its rounded end, past its outline.
        const val INSIDE = 4f

        // How far two pixels' channels may differ, summed, and still be the same colour.
        const val TOLERANCE = 6

        // How far apart, summed, two colours are to be told apart at a glance.
        const val DISTINCT = 60

        const val ROW_GAP_DP = 6f
        const val TARGET_DP = 48f

        // How far from a chip's edge, or from halfway between two, a touch lands, in dp.
        const val NEAR = 4f
    }
}
