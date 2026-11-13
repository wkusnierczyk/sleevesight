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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
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
import app.sleevesight.core.evidence.Scope
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

    // The unmerged tree: a chip, a name or a status is part of its row's switch (#1223).
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
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

    /** The row of [tag] is one switch (#1223), read first by its source's [name], its lines [shown] or not. */
    private fun switch(
        tag: String,
        name: String,
        shown: Boolean,
    ) = compose
        .onNodeWithTag(ROW_PREFIX + tag)
        .assert(SemanticsMatcher("read first as $name") { said(tag).firstOrNull() == name })
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
            assertEquals(listOf("MB", "W", "S", "AI"), rows())
            // Each row says its source's name and what it found (#1223); a rejected match is nothing found.
            val found = text(R.string.home_source_found, text(R.string.home_source_finding_track))
            assertEquals(listOf(text(R.string.settings_source_musicbrainz), found), said("MB"))
            assertEquals(listOf(text(R.string.home_source_chip_wikipedia), found), said("W"))
            assertEquals(listOf(text(R.string.home_source_name_web), text(R.string.home_source_none)), said("S"))
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
    fun eachSourceSaysWhatItsOwnLinesFoundAsAList() {
        val artist = draft(SourceId.MUSICBRAINZ, "Gordon Lightfoot is a Canadian singer.", scope = Scope.ARTIST)
        val original = draft(SourceId.WIKIDATA, "It is a cover.", scope = Scope.ORIGINAL_SONG)
        val evidence =
            EvidenceSet.of(listOf(musicBrainzLine, artist, wikipediaLine, original, HomeFixtures.webLine), JvmHasher)
        val sources =
            mapOf(
                SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine, artist)),
                // The outcome says the artist; the lines say more, Wikidata's too.
                SourceId.WIKIPEDIA to done(Outcome.ArtistArticle, evidence = listOf(wikipediaLine, original)),
                SourceId.WEB to done(evidence = listOf(HomeFixtures.webLine)),
            )
        show(track(sources = sources, evidence = evidence), needsKey = emptySet()) {
            unfold()
            val track = text(R.string.home_source_finding_track)
            val and = text(R.string.home_source_finding_and, track, text(R.string.home_source_finding_artist))
            assertEquals(
                listOf(text(R.string.settings_source_musicbrainz), text(R.string.home_source_found, and)),
                said("MB"),
            )
            val pair = text(R.string.home_source_finding_and, track, text(R.string.home_source_finding_original_song))
            assertEquals(
                listOf(text(R.string.home_source_chip_wikipedia), text(R.string.home_source_found, pair)),
                said("W"),
            )
            assertEquals(
                listOf(text(R.string.home_source_name_web), text(R.string.home_source_found_pages)),
                said("S"),
            )
            // The words as the owner picked them.
            assertEquals("Found the track and the artist", text(R.string.home_source_found, and))
            assertEquals("Found pages about the track", text(R.string.home_source_found_pages))
        }
    }

    @Test
    fun aRowWithLinesIsASwitchNamedByItsSourceAndADimmedOneIsNoButton() =
        show(mixed) {
            unfold()
            switch("MB", text(R.string.settings_source_musicbrainz), shown = true)
            switch("W", text(R.string.home_source_chip_wikipedia), shown = true)
            assertEquals(
                text(R.string.home_source_filter, text(R.string.home_source_chip_wikipedia)),
                compose
                    .onNodeWithTag(ROW_PREFIX + "W")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .label,
            )
            for (tag in listOf("S", "AI")) {
                val row = compose.onNodeWithTag(ROW_PREFIX + tag).fetchSemanticsNode().config
                assertFalse(tag, SemanticsActions.OnClick in row)
                assertFalse(tag, SemanticsProperties.Role in row)
                assertFalse(tag, SemanticsProperties.ToggleableState in row)
            }
            // A dimmed chip's row is read as one text: the source's name and what it found.
            val web = text(R.string.home_source_name_web)
            compose
                .onNode(hasText(web) and hasText(text(R.string.home_source_none)))
                .assertTextEquals(web, text(R.string.home_source_none))
            // The row answers a touch across its whole height, at least 48 dp, and its width.
            compose
                .onNodeWithTag(ROW_PREFIX + "W")
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
            val row =
                compose
                    .onNodeWithTag(ROW_PREFIX + "W")
                    .fetchSemanticsNode()
                    .size.width
            val table =
                compose
                    .onNodeWithTag(SOURCES_TABLE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .size.width
            assertEquals(table, row)
        }

    // #1223: a screen reader stops once on each row, a switch, and not again on its chip or its words.
    @Test
    fun aScreenReaderFindsOneSwitchPerRowAndNoOtherStopInIt() =
        show(mixed) {
            unfold()
            val switches =
                compose
                    .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                    .fetchSemanticsNodes()
                    .filter { it.tag()?.startsWith(ROW_PREFIX) == true || inTable(it) }
            assertEquals(listOf(ROW_PREFIX + "MB", ROW_PREFIX + "W"), switches.map { it.tag() })
            for (tag in rows()) {
                // Nothing in a row is a node of its own in the tree a screen reader is given: no
                // chip, no name, no status.
                val inRow =
                    compose
                        .onAllNodes(hasAnyAncestor(hasTestTag(ROW_PREFIX + tag)))
                        .fetchSemanticsNodes()
                assertEquals(tag, emptyList<SemanticsNode>(), inRow)
                // Nor a click of its own on the chip, which only shows the state.
                val chip = compose.onNodeWithTag(CHIP_PREFIX + tag, useUnmergedTree = true).fetchSemanticsNode().config
                assertFalse(tag, SemanticsActions.OnClick in chip)
                assertFalse(tag, SemanticsProperties.Role in chip)
            }
            // A source's name is read once, in its row.
            val musicBrainz = text(R.string.settings_source_musicbrainz)
            assertEquals(
                listOf(ROW_PREFIX + "MB"),
                compose.onAllNodesWithText(musicBrainz).fetchSemanticsNodes().map { it.tag() },
            )
        }

    private fun inTable(node: SemanticsNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent.tag() == SOURCES_TABLE_TAG) return true
            parent = parent.parent
        }
        return false
    }

    // #1223: the name and what the source found toggle it as its chip does.
    @Test
    fun aTapOnASourcesNameOrWhatItFoundHidesAndShowsItsLines() =
        show(mixed) {
            unfold()
            val wikipedia = text(R.string.home_source_chip_wikipedia)
            compose.onNodeWithText(wikipedia, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            switch("W", wikipedia, shown = false)
            assertEquals(0, count(LINE_PREFIX + "W1"))
            assertEquals(1, count(LINE_PREFIX + "MB1"))
            tap(STATUS_PREFIX + "W")
            switch("W", wikipedia, shown = true)
            assertEquals(1, count(LINE_PREFIX + "W1"))
            tap(STATUS_PREFIX + "MB")
            switch("MB", text(R.string.settings_source_musicbrainz), shown = false)
            assertEquals(0, count(LINE_PREFIX + "MB1"))
            // A dimmed row's words do nothing: the lines stay as they were.
            tap(STATUS_PREFIX + "S")
            switch("W", wikipedia, shown = true)
            switch("MB", text(R.string.settings_source_musicbrainz), shown = false)
            assertEquals(1, count(LINE_PREFIX + "W1"))
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
            val dimmed = picture(CHIP_PREFIX + "S")
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
            val rows = rows().map(::rowBounds)
            // Each row its own touch area, at least 48 dp, the next beginning where it ends (#1223).
            val gaps = rows.zipWithNext { above, below -> (below.first - above.second) / density() }
            assertTrue("rows abut: $gaps", gaps.all { abs(it) < 1f })
            val heights = rows.map { (top, bottom) -> (bottom - top) / density() }
            assertTrue("rows 48 dp at the least: $heights", heights.all { it >= TARGET_DP - 0.5f })
            val chips =
                listOf("MB", "W", "S", "AI").map {
                    compose.onNodeWithTag(CHIP_PREFIX + it, useUnmergedTree = true).fetchSemanticsNode()
                }
            val ends = chips.map { it.positionInRoot.x + it.size.width }
            assertTrue("one right edge: $ends", ends.maxOf { it } - ends.minOf { it } < 1f)
            // A chip lays out its own height, not a 48 dp target: the row is the target.
            for (chip in chips) assertTrue("${chip.size.height} px", chip.size.height / density() < TARGET_DP)
        }

    private fun rowBounds(tag: String) =
        compose.onNodeWithTag(ROW_PREFIX + tag, useUnmergedTree = true).fetchSemanticsNode().let {
            it.positionInRoot.y to it.positionInRoot.y + it.size.height
        }

    /** Touches the screen at [dp] from [row]'s top (negative above it), at [x] px from the left. */
    private fun touch(
        row: String,
        dp: Float,
        x: Float,
    ) {
        val (top, _) = rowBounds(row)
        compose.onRoot().performTouchInput { click(Offset(x, top + dp * density())) }
        compose.waitForIdle()
    }

    private fun shown(tag: String) =
        compose.onNodeWithTag(ROW_PREFIX + tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
            ToggleableState.On

    /**
     * Touches just inside a row's top and bottom edges, over its chip and over its words (#1223):
     * that row toggles, and only it; one in the dimmed row toggles nothing. At [fontScale].
     */
    private fun touchesGoToTheirRow(fontScale: Float) =
        show(mixed, fontScale = fontScale) {
            unfold()
            compose.onNodeWithTag(ROW_PREFIX + "S", useUnmergedTree = true).performScrollTo()
            val chip = compose.onNodeWithTag(CHIP_PREFIX + "MB", useUnmergedTree = true).fetchSemanticsNode()
            val overChip = chip.boundsInRoot.center.x
            val overWords =
                compose
                    .onNodeWithTag(STATUS_PREFIX + "MB", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x
            val height = (rowBounds("MB").second - rowBounds("MB").first) / density()
            for (x in listOf(overChip, overWords)) {
                // Just inside MusicBrainz's row, at its top and at its bottom.
                touch("MB", NEAR, x)
                assertEquals(false to true, shown("MB") to shown("W"))
                touch("MB", height - NEAR, x)
                assertEquals(true to true, shown("MB") to shown("W"))
                // Just inside Wikipedia's, below it.
                touch("W", NEAR, x)
                assertEquals(true to false, shown("MB") to shown("W"))
                touch("W", NEAR, x)
                // In the dimmed web search's row: nothing.
                touch("S", NEAR, x)
                assertEquals(true to true, shown("MB") to shown("W"))
            }
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTouchAnywhereInARowGoesToItsRow() = touchesGoToTheirRow(1f)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTouchAnywhereInARowGoesToItsRowInALargeFont() = touchesGoToTheirRow(2f)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theRowsAreAtLeastATouchAreaApartSoTheTargetsDoNotOverlap() =
        show(mixed) {
            unfold()
            // Unclipped: a chip scrolled out of view has empty bounds in the root.
            val centres =
                listOf("MB", "W", "S", "AI").map {
                    val chip = compose.onNodeWithTag(CHIP_PREFIX + it, useUnmergedTree = true).fetchSemanticsNode()
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
            assertNotEquals(colour("MB"), colour("S"))
        }
        show(mixed) {
            unfold()
            assertEquals(colour("MB"), colour("S"))
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
            assertEquals(listOf(text(R.string.home_source_chip_player), text(R.string.home_source_release)), said("P"))
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
                said("S"),
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
            assertEquals(listOf("MB", "W", "D", "S", "L", "AI"), rows())
            assertEquals(
                listOf(
                    text(R.string.settings_source_musicbrainz),
                    text(
                        R.string.home_source_cached,
                        text(R.string.home_source_found, text(R.string.home_source_finding_track)),
                    ),
                ),
                said("MB"),
            )
            assertEquals(listOf(text(R.string.home_source_name_web), text(R.string.home_source_rejected)), said("S"))
            assertEquals(listOf(text(R.string.home_source_name_discogs), text(R.string.home_source_off)), said("D"))
            assertEquals(
                listOf(text(R.string.settings_source_lastfm), text(R.string.home_source_needs_key)),
                said("L"),
            )
            // Still a switch where it has lines, dimmed where it has none.
            switch("MB", text(R.string.settings_source_musicbrainz), shown = true)
            val discogs = compose.onNodeWithTag(ROW_PREFIX + "D").fetchSemanticsNode().config
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
            for (tag in listOf("MB", "W", "S", "L", "AI")) assertEquals(tag, 0, count(CHIP_PREFIX + tag))
            assertEquals(0, countText(text(R.string.home_rejected_title)))
            assertEquals(0, count(SOURCES_NOTE_TAG))
        }

    // The closed legend's words (#951); the open legend's are in `SourcesLegendScreenTest`.
    private val legendWords = listOf(R.string.home_sources_legend_explain)

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let {
            it.positionInRoot.y to it.positionInRoot.y + it.size.height
        }

    @Test
    fun theLegendShowsUnderTheTableBeforeTheNote() =
        show(mixed) {
            // Folded: no table, no legend.
            assertEquals(0, count(SOURCES_LEGEND_TAG))
            unfold()
            for (word in legendWords) {
                val inLegend = hasText(text(word)) and hasAnyAncestor(hasTestTag(SOURCES_LEGEND_TAG))
                val found = compose.onAllNodes(inLegend, useUnmergedTree = true).fetchSemanticsNodes()
                assertEquals(text(word), 1, found.size)
            }
            assertEquals(1, count(SOURCES_LEGEND_ASK_TAG))
            val (_, tableBottom) = bounds(SOURCES_TABLE_TAG)
            val (legendTop, legendBottom) = bounds(SOURCES_LEGEND_TAG)
            val (noteTop, _) = bounds(SOURCES_NOTE_TAG)
            assertTrue("legend at $legendTop, table ends at $tableBottom", legendTop >= tableBottom)
            assertTrue("legend ends at $legendBottom, note at $noteTop", legendBottom <= noteTop)
        }

    @Test
    fun aTableWithNoRowsHasNoLegend() =
        show(track(sources = emptyMap(), evidence = null), settings = Settings(), needsKey = emptySet()) {
            unfold()
            assertEquals(0, count(SOURCES_TABLE_TAG))
            assertEquals(0, count(SOURCES_LEGEND_TAG))
            for (word in legendWords) assertEquals(text(word), 0, countText(text(word)))
        }

    // The chip as drawn, its rounded shape and no more, scrolled into view.
    private fun picture(tag: String): Bitmap =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
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

        const val TARGET_DP = 48f

        // How far inside a row's edge a touch lands, in dp.
        const val NEAR = 4f
    }
}
