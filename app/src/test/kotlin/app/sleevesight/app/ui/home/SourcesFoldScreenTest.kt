package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The *Sources* list's fold (#269), its heading since #440: each state, the moves between them, and
 * what unfolds it.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesFoldScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun plural(
        id: Int,
        count: Int,
    ) = app.resources.getQuantityString(id, count, count)

    /** [playing]'s track with [count] MusicBrainz lines, then [wikipedia] Wikipedia lines. */
    private fun long(
        count: Int,
        playing: NowPlaying = sundown,
        wikipedia: Int = 0,
        insight: InsightState = InsightState.Idle(canWrite = false),
    ): TrackState {
        val mb = (1..count).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val w = (1..wikipedia).map { draft(SourceId.WIKIPEDIA, "Article fact $it.") }
        val sources =
            mapOf(SourceId.MUSICBRAINZ to done(evidence = mb)) +
                if (w.isEmpty()) emptyMap() else mapOf(SourceId.WIKIPEDIA to done(evidence = w))
        return track(playing, insight, sources, EvidenceSet.of(mb + w, JvmHasher))
    }

    private fun state(
        track: TrackState,
        settings: Settings = Settings(),
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content
    // and composes it afresh from what was saved, as Android does for a recreated activity (what
    // `StateRestorationTester` does, which needs a content rule these tests do not use).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun show(
        initial: NowPlayingState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            scenario.onActivity { block(current) }
        }
    }

    private fun announced() =
        compose
            .onNodeWithTag(FOLD_STATUS_TAG)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite

    private fun tap(tag: String) {
        compose
            .onAllNodesWithTag(tag)
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** How many evidence lines are laid out. */
    private fun linesShown(): Int =
        compose
            .onAllNodes(
                SemanticsMatcher("an evidence line") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(LINE_PREFIX) == true
                },
            ).fetchSemanticsNodes()
            .size

    private fun status(expected: String) = compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(expected)

    /** Which paging controls show, by tag, as a set. */
    private fun controls(): Set<String> =
        listOf(FOLD_MORE_TAG, FOLD_ALL_TAG, FOLD_LESS_TAG).filter { count(it) > 0 }.toSet()

    /** The heading as drawn: `Sources` and its disclosure triangle. */
    private fun heading(folded: Boolean) =
        text(R.string.home_sources_title) + " " +
            text(if (folded) R.string.home_fold_folded else R.string.home_fold_unfolded)

    // The heading's state as a screen reader says it: its triangle drawn is not read.
    private fun headingDrawn(folded: Boolean) =
        compose
            .onNodeWithTag(SOURCES_FOLD_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(if (folded) R.string.home_fold_collapsed else R.string.home_fold_expanded),
                ),
            )

    @Test
    fun theListStartsFoldedAsItsHeadingAlone() =
        show(state(long(20, wikipedia = 3))) {
            compose.onNodeWithTag(SOURCES_FOLD_TAG).assertExists()
            // No chips, no note, no count, no controls: the heading's line only.
            assertEquals(0, count(CHIP_PREFIX + "MB"))
            assertEquals(0, count(CHIP_PREFIX + "W"))
            assertEquals(0, count(FOLD_STATUS_TAG))
            assertEquals(emptySet<String>(), controls())
            assertEquals(0, linesShown())
        }

    @Test
    fun theHeadingUnfoldsTheFirstScreenfulWithShowMoreAndShowAllUnderIt() =
        show(state(long(20))) {
            tap(SOURCES_FOLD_TAG)
            assertEquals(Fold.PAGE, linesShown())
            compose.onNodeWithTag(LINE_PREFIX + "MB8").assertExists()
            assertEquals(0, count(LINE_PREFIX + "MB9"))
            compose.onNodeWithTag(CHIP_PREFIX + "MB").assertExists()
            // No *less* at the first screenful.
            assertEquals(setOf(FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
            status(text(R.string.home_sources_showing, 8, 20))
        }

    @Test
    fun showMoreAddsAScreenfulUntilTheEndAndShowLessGoesBackToTheFirst() =
        show(state(long(20))) {
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            assertEquals(16, linesShown())
            assertEquals(setOf(FOLD_LESS_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
            status(text(R.string.home_sources_showing, 16, 20))

            tap(FOLD_MORE_TAG)
            assertEquals(20, linesShown())
            // Nothing left: *show more…* and *show all* go, *… show less* stays.
            assertEquals(setOf(FOLD_LESS_TAG), controls())
            status(plural(R.plurals.home_sources_showing_all, 20))

            tap(FOLD_LESS_TAG)
            assertEquals(Fold.PAGE, linesShown())
            assertEquals(setOf(FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
        }

    @Test
    fun showAllShowsEveryLineWithShowLess() =
        show(state(long(20))) {
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_ALL_TAG)
            assertEquals(20, linesShown())
            assertEquals(setOf(FOLD_LESS_TAG), controls())
        }

    @Test
    fun theHeadingFoldsItFromEveryUnfoldedStateWithItsTriangleTurned() =
        show(state(long(20))) {
            headingDrawn(folded = true)
            for (unfold in listOf(emptyList(), listOf(FOLD_MORE_TAG), listOf(FOLD_ALL_TAG))) {
                tap(SOURCES_FOLD_TAG)
                unfold.forEach(::tap)
                headingDrawn(folded = false)
                tap(SOURCES_FOLD_TAG)
                headingDrawn(folded = true)
                assertEquals("$unfold", 0, linesShown())
                assertEquals(emptySet<String>(), controls())
                assertEquals(0, count(CHIP_PREFIX + "MB"))
                assertEquals(0, count(FOLD_STATUS_TAG))
            }
        }

    @Test
    fun aListOfAScreenfulOrLessUnfoldsWholeWithNoPaging() =
        show(state(track())) {
            tap(SOURCES_FOLD_TAG)
            assertEquals(3, linesShown())
            assertEquals(emptySet<String>(), controls())
            status(plural(R.plurals.home_sources_showing_all, 3))
        }

    @Test
    fun aCitationTappedWhileFoldedUnfoldsToItsLineAndShowsIt() {
        val evidence = checkNotNull(long(20).evidence)
        val reply = GroundingCheck.check("It is documented [MB12].", evidence, allowModel = false)
        val checked = (reply as InsightReply.Written).grounded
        show(state(long(20, insight = InsightState.Written(checked, cached = false)))) {
            assertEquals(0, linesShown())
            compose.onNodeWithText("It is documented", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "MB12"
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "MB12").assertIsDisplayed()
            // Unfolded by whole screenfuls, so *show more…* goes on from there.
            assertEquals(16, linesShown())
            assertEquals(setOf(FOLD_LESS_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
        }
    }

    @Test
    fun thePagingReadsShowMoreShowLessAndShowAllInPlainText() {
        assertEquals("show more…", text(R.string.home_sources_more))
        assertEquals("… show less", text(R.string.home_sources_less))
        assertEquals("show all", text(R.string.home_sources_all))
    }

    @Test
    fun theHeadingIsAButtonReadAsSourcesCollapsedOrExpanded() =
        show(state(long(20))) {
            fun check(expanded: Boolean) {
                compose
                    .onNodeWithTag(SOURCES_FOLD_TAG)
                    .performScrollTo()
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ContentDescription,
                            listOf(text(R.string.home_sources_title)),
                        ),
                    ).assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            text(if (expanded) R.string.home_fold_expanded else R.string.home_fold_collapsed),
                        ),
                    ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                    .assertTouchHeightIsEqualTo(48.dp)
                // The triangle is not read: no node says the heading as drawn.
                for (folded in listOf(true, false)) {
                    assertEquals(0, compose.onAllNodesWithText(heading(folded)).fetchSemanticsNodes().size)
                }
            }
            check(expanded = false)
            tap(SOURCES_FOLD_TAG)
            check(expanded = true)
        }

    @Test
    fun thePagingControlsAreButtonsNamedInWordsWith48DpTargets() =
        show(state(long(20))) {
            val words =
                mapOf(
                    FOLD_MORE_TAG to R.string.home_sources_more_words,
                    FOLD_ALL_TAG to R.string.home_sources_all_words,
                    FOLD_LESS_TAG to R.string.home_sources_less_words,
                )
            val drawn =
                listOf(
                    R.string.home_sources_more,
                    R.string.home_sources_all,
                    R.string.home_sources_less,
                ).map(::text)

            fun check(tag: String) {
                compose
                    .onAllNodesWithTag(tag)
                    .onFirst()
                    .performScrollTo()
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ContentDescription,
                            listOf(text(words.getValue(tag))),
                        ),
                    ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                    .assertTouchHeightIsEqualTo(48.dp)
                // The arrows and dots are not read.
                for (label in drawn) {
                    assertEquals(label, 0, compose.onAllNodesWithText(label).fetchSemanticsNodes().size)
                }
            }

            tap(SOURCES_FOLD_TAG)
            check(FOLD_MORE_TAG)
            check(FOLD_ALL_TAG)
            tap(FOLD_MORE_TAG)
            check(FOLD_LESS_TAG)
            // How much shows is announced as it changes.
            compose
                .onNodeWithTag(FOLD_STATUS_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            // A chip is the filter switch it was.
            compose
                .onNodeWithTag(CHIP_PREFIX + "MB")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        }

    @Test
    fun aNewTrackStartsFoldedByDefault() =
        show(state(long(20))) { current ->
            tap(SOURCES_FOLD_TAG)
            assertEquals(Fold.PAGE, linesShown())
            current.value = state(long(20, playing = carefree))
            compose.waitForIdle()
            assertEquals(0, linesShown())
            headingDrawn(folded = true)
        }

    @Test
    fun aNewTrackKeepsTheFoldAsLeftWithTheSettingOnAtItsFirstScreenful() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            assertEquals(16, linesShown())
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())

            tap(FOLD_ALL_TAG)
            current.value = state(long(20, playing = sundown), keep)
            compose.waitForIdle()
            assertEquals(20, linesShown())

            tap(SOURCES_FOLD_TAG)
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(0, linesShown())
        }
    }

    @Test
    fun theSameTrackKeepsItsFoldWhenItsEvidenceChanges() =
        show(state(long(20))) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            // The sources asked again (back online): a new evidence set for the track playing on.
            current.value = state(long(21))
            compose.waitForIdle()
            assertEquals(16, linesShown())
        }

    @Test
    fun unverifiedLinesAndWithGroundingDetailsRejectedMatchesFoldWithTheRestUnderTheirHeadings() {
        val mb = (1..7).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val unverified: List<EvidenceDraft> =
            listOf(draft(SourceId.WIKIPEDIA, "Perhaps one."), draft(SourceId.WIKIPEDIA, "Perhaps two."))
        val sources =
            mapOf(
                SourceId.MUSICBRAINZ to done(evidence = mb),
                SourceId.WIKIPEDIA to
                    done(
                        unverified = unverified,
                        rejections = listOf(Rejection("Sundown (film)", "a film", Scope.SONG)),
                    ),
            )
        val details = Settings(showGroundingDetails = true)
        show(state(track(sources = sources, evidence = EvidenceSet.of(mb, JvmHasher)), details)) {
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_unverified)).fetchSemanticsNodes().size)

            tap(SOURCES_FOLD_TAG)
            // The first screenful: 7 lines and the first unverified one, under its heading.
            assertEquals(7, linesShown())
            compose.onNodeWithText(text(R.string.home_unverified)).assertExists()
            compose.onNodeWithText("Perhaps one.").assertExists()
            assertEquals(0, compose.onAllNodesWithText("Perhaps two.").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_rejected_title)).fetchSemanticsNodes().size)
            status(text(R.string.home_sources_showing, 8, 10))

            tap(FOLD_MORE_TAG)
            compose.onNodeWithText("Perhaps two.").assertExists()
            compose.onNodeWithText(text(R.string.home_rejected_title)).assertExists()
            val wikipedia = text(R.string.settings_source_wikipedia)
            val rejected = text(R.string.home_rejected_subject, wikipedia, "Sundown (film)", "a film")
            compose.onNodeWithText(rejected).assertExists()
        }
        // Without the setting, no rejected match: the 7 lines and the 2 unverified ones.
        show(state(track(sources = sources, evidence = EvidenceSet.of(mb, JvmHasher)))) {
            tap(SOURCES_FOLD_TAG)
            status(text(R.string.home_sources_showing, 8, 9))
            tap(FOLD_MORE_TAG)
            status(plural(R.plurals.home_sources_showing_all, 9))
            compose.onNodeWithText("Perhaps two.").assertExists()
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_rejected_title)).fetchSemanticsNodes().size)
        }
    }

    /** [track] with an insight citing [id] in its evidence. */
    private fun citing(
        track: TrackState,
        id: String,
    ): TrackState {
        val reply = GroundingCheck.check("It is documented [$id].", checkNotNull(track.evidence), allowModel = false)
        return track.copy(insight = InsightState.Written((reply as InsightReply.Written).grounded, cached = false))
    }

    private fun cite(id: String) {
        compose.onNodeWithText("It is documented", substring = true).performScrollTo().performFirstLinkClick {
            (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + id
        }
        compose.waitForIdle()
    }

    @Test
    fun aTrackPlayedAgainAfterOthersStartsFoldedWithNoFoldChangedOnTheOthers() =
        show(state(long(20))) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            current.value = state(long(20, playing = carefree))
            compose.waitForIdle()
            current.value = state(long(20))
            compose.waitForIdle()
            assertEquals(0, linesShown())
        }

    @Test
    fun withTheSettingOnATrackPlayedAgainAfterOthersComesBackAtItsFirstScreenful() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            current.value = state(long(20), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())
        }
    }

    @Test
    fun theFoldOutlivesARestoredScreen() =
        show(state(long(20))) {
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            restore()
            assertEquals(16, linesShown())
        }

    @Test
    fun theFoldOutlivesAWhileWithNothingPlaying() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            current.value = NowPlayingState(PipelineView(), playing = null, settings = keep)
            compose.waitForIdle()
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())
        }
    }

    @Test
    fun howMuchShowsIsAnnouncedOnlyOnceTheFoldWasChangedOnTheTrack() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            assertTrue(announced())
            // The next track, unfolded as left: not announced until the listener changes the fold.
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertFalse(announced())
            // Sources landing change the count by themselves: not announced.
            current.value = state(long(21, playing = carefree), keep)
            compose.waitForIdle()
            assertFalse(announced())
            tap(FOLD_MORE_TAG)
            assertTrue(announced())
        }
    }

    @Test
    fun aCitationOfALineWhoseSourceAChipHidWhilePartlyUnfoldedReachesIt() =
        show(state(citing(long(20, wikipedia = 3), "W3"))) {
            tap(SOURCES_FOLD_TAG)
            tap(CHIP_PREFIX + "W")
            assertEquals(0, count(LINE_PREFIX + "W3"))
            cite("W3")
            compose.onNodeWithTag(LINE_PREFIX + "W3").assertIsDisplayed()
        }

    @Test
    fun aCitationOfTheLastLineShowsTheWholeList() =
        show(state(citing(long(20), "MB20"))) {
            cite("MB20")
            compose.onNodeWithTag(LINE_PREFIX + "MB20").assertIsDisplayed()
            assertEquals(20, linesShown())
            assertEquals(setOf(FOLD_LESS_TAG), controls())
        }

    @Test
    fun aChipShowingItsLinesAgainWhilePartlyUnfoldedUnfoldsToThem() =
        show(state(long(20, wikipedia = 3))) {
            tap(SOURCES_FOLD_TAG)
            tap(CHIP_PREFIX + "W")
            tap(CHIP_PREFIX + "W")
            // Unfolded to, and scrolled to: it is far below the chip tapped.
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            assertEquals(23, linesShown())
        }

    @Test
    fun withEveryEntryHiddenByTheChipsTheCountSaysSoAndUnfoldingAgainShowsThem() =
        show(state(track())) {
            tap(SOURCES_FOLD_TAG)
            tap(CHIP_PREFIX + "MB")
            tap(CHIP_PREFIX + "W")
            assertEquals(0, linesShown())
            assertEquals(emptySet<String>(), controls())
            status(plural(R.plurals.home_sources_filtered, 3))
            tap(SOURCES_FOLD_TAG)
            assertEquals(0, count(FOLD_STATUS_TAG))
            // Unfolding then shows the lines the chips hid, not an empty list.
            tap(SOURCES_FOLD_TAG)
            assertEquals(3, linesShown())
            compose
                .onNodeWithTag(CHIP_PREFIX + "MB")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(R.string.home_source_lines_shown),
                    ),
                )
        }

    @Test
    fun aTrackChangeWhileTheSettingsAreUnreadFoldsTheListWhenTheyKeepNone() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            // The settings unread across the track change, then read with the setting off.
            current.value =
                state(long(20, playing = carefree)).let { NowPlayingState(it.view, it.playing, it.playback) }
            compose.waitForIdle()
            current.value = state(long(20, playing = carefree), Settings())
            compose.waitForIdle()
            assertEquals(0, linesShown())
            tap(SOURCES_FOLD_TAG)
            assertEquals(Fold.PAGE, linesShown())
        }
    }

    @Test
    fun aTrackChangeWhileTheSettingsAreUnreadKeepsTheFoldTheyKeep() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(SOURCES_FOLD_TAG)
            tap(FOLD_MORE_TAG)
            // Back from another screen: the settings not read yet, and the next track playing.
            current.value =
                state(long(20, playing = carefree)).let { NowPlayingState(it.view, it.playing, it.playback) }
            compose.waitForIdle()
            assertEquals(0, linesShown())
            restore()
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())
        }
    }
}
