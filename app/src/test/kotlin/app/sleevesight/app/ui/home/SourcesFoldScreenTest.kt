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
import androidx.compose.ui.test.onLast
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
import org.robolectric.annotation.Config

/** The *Sources* list's fold (#269): each state, the moves between them, and what unfolds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    /** Which controls show, by tag, as a set. */
    private fun controls(): Set<String> =
        listOf(FOLD_SHOW_TAG, FOLD_HIDE_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG, FOLD_LESS_TAG)
            .flatMap { tag -> List(count(tag)) { tag } }
            .groupingBy { it }
            .eachCount()
            .map { (tag, n) -> if (n > 1) "$tag x$n" else tag }
            .toSet()

    @Test
    fun theListStartsFoldedWithItsHeadingChipsAndShow() =
        show(state(long(20))) {
            compose.onNodeWithText(text(R.string.home_sources_title)).assertExists()
            compose.onNodeWithTag(CHIP_PREFIX + "MB").assertExists()
            assertEquals(setOf(FOLD_SHOW_TAG), controls())
            assertEquals(0, linesShown())
            status(plural(R.plurals.home_sources_folded, 20))
        }

    @Test
    fun showUnfoldsTheFirstScreenfulWithMoreAndShowAllUnderIt() =
        show(state(long(20))) {
            tap(FOLD_SHOW_TAG)
            assertEquals(Fold.PAGE, linesShown())
            compose.onNodeWithTag(LINE_PREFIX + "MB8").assertExists()
            assertEquals(0, count(LINE_PREFIX + "MB9"))
            // *hide* above the list and under it; no *less* at the first screenful.
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
            status(text(R.string.home_sources_showing, 8, 20))
        }

    @Test
    fun moreAddsAScreenfulUntilTheEndAndLessGoesBackToTheFirst() =
        show(state(long(20))) {
            tap(FOLD_SHOW_TAG)
            tap(FOLD_MORE_TAG)
            assertEquals(16, linesShown())
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_LESS_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
            status(text(R.string.home_sources_showing, 16, 20))

            tap(FOLD_MORE_TAG)
            assertEquals(20, linesShown())
            // Nothing left: *more* and *show all* go, *less* stays.
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_LESS_TAG), controls())
            status(plural(R.plurals.home_sources_showing_all, 20))

            tap(FOLD_LESS_TAG)
            assertEquals(Fold.PAGE, linesShown())
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
        }

    @Test
    fun showAllShowsEveryLineWithLessAndHide() =
        show(state(long(20))) {
            tap(FOLD_SHOW_TAG)
            tap(FOLD_ALL_TAG)
            assertEquals(20, linesShown())
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_LESS_TAG), controls())
        }

    @Test
    fun hideAboveOrUnderTheListFoldsItFromEveryUnfoldedState() =
        show(state(long(20))) {
            for (unfold in listOf(emptyList(), listOf(FOLD_MORE_TAG), listOf(FOLD_ALL_TAG))) {
                for (top in listOf(true, false)) {
                    tap(FOLD_SHOW_TAG)
                    unfold.forEach(::tap)
                    val hides = compose.onAllNodesWithTag(FOLD_HIDE_TAG)
                    (if (top) hides.onFirst() else hides.onLast()).performScrollTo().performClick()
                    compose.waitForIdle()
                    assertEquals("$unfold, top $top", 0, linesShown())
                    assertEquals(setOf(FOLD_SHOW_TAG), controls())
                }
            }
        }

    @Test
    fun aListOfAScreenfulOrLessUnfoldsWholeWithHideOnly() =
        show(state(track())) {
            tap(FOLD_SHOW_TAG)
            assertEquals(3, linesShown())
            assertEquals(setOf("$FOLD_HIDE_TAG x2"), controls())
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
            // Unfolded by whole screenfuls, so *more* goes on from there.
            assertEquals(16, linesShown())
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_LESS_TAG, FOLD_MORE_TAG, FOLD_ALL_TAG), controls())
        }
    }

    @Test
    fun aChipChosenWhileFoldedUnfoldsToItsSourcesFirstLineEvenIfItHidThem() =
        show(state(long(20, wikipedia = 3))) {
            tap(CHIP_PREFIX + "W")
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            assertEquals(23, linesShown())

            // Unfolded, the chip hides its lines; folded again, it shows them and unfolds to them.
            tap(FOLD_LESS_TAG)
            tap(CHIP_PREFIX + "W")
            assertEquals(Fold.PAGE, linesShown())
            tap(FOLD_HIDE_TAG)
            tap(CHIP_PREFIX + "W")
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            compose
                .onNodeWithTag(CHIP_PREFIX + "W")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(R.string.home_source_lines_shown),
                    ),
                )
        }

    @Test
    fun theControlsAreButtonsNamedInWordsWith48DpTargets() =
        show(state(long(20))) {
            val words =
                mapOf(
                    FOLD_SHOW_TAG to R.string.home_sources_show_words,
                    FOLD_HIDE_TAG to R.string.home_sources_hide_words,
                    FOLD_MORE_TAG to R.string.home_sources_more_words,
                    FOLD_ALL_TAG to R.string.home_sources_all_words,
                    FOLD_LESS_TAG to R.string.home_sources_less_words,
                )
            val drawn =
                listOf(
                    R.string.home_sources_show,
                    R.string.home_sources_hide,
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

            check(FOLD_SHOW_TAG)
            // Not announced until the listener changes the fold.
            assertFalse(announced())
            // A folded chip is a button that shows its source's lines.
            val chip = compose.onNodeWithTag(CHIP_PREFIX + "MB")
            chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals(
                text(R.string.home_source_reveal, text(R.string.settings_source_musicbrainz)),
                chip.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
            )
            tap(FOLD_SHOW_TAG)
            check(FOLD_HIDE_TAG)
            check(FOLD_MORE_TAG)
            check(FOLD_ALL_TAG)
            tap(FOLD_MORE_TAG)
            check(FOLD_LESS_TAG)
            // How much shows is announced as it changes.
            compose
                .onNodeWithTag(FOLD_STATUS_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            // An unfolded chip is the filter switch it was.
            chip.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        }

    @Test
    fun aNewTrackStartsFoldedByDefault() =
        show(state(long(20))) { current ->
            tap(FOLD_SHOW_TAG)
            assertEquals(Fold.PAGE, linesShown())
            current.value = state(long(20, playing = carefree))
            compose.waitForIdle()
            assertEquals(0, linesShown())
            assertEquals(setOf(FOLD_SHOW_TAG), controls())
        }

    @Test
    fun aNewTrackKeepsTheFoldAsLeftWithTheSettingOnAtItsFirstScreenful() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(FOLD_SHOW_TAG)
            tap(FOLD_MORE_TAG)
            assertEquals(16, linesShown())
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())

            tap(FOLD_ALL_TAG)
            current.value = state(long(20, playing = sundown), keep)
            compose.waitForIdle()
            assertEquals(20, linesShown())

            tap(FOLD_HIDE_TAG)
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(0, linesShown())
        }
    }

    @Test
    fun theSameTrackKeepsItsFoldWhenItsEvidenceChanges() =
        show(state(long(20))) { current ->
            tap(FOLD_SHOW_TAG)
            tap(FOLD_MORE_TAG)
            // The sources asked again (back online): a new evidence set for the track playing on.
            current.value = state(long(21))
            compose.waitForIdle()
            assertEquals(16, linesShown())
        }

    @Test
    fun unverifiedLinesAndRejectedMatchesFoldWithTheRestUnderTheirHeadings() {
        val mb = (1..7).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val unverified: List<EvidenceDraft> =
            listOf(draft(SourceId.WIKIPEDIA, "Perhaps one."), draft(SourceId.WIKIPEDIA, "Perhaps two."))
        val sources =
            mapOf(
                SourceId.MUSICBRAINZ to done(evidence = mb),
                SourceId.WIKIPEDIA to
                    done(unverified = unverified, rejections = listOf(Rejection("Sundown (film)", "a film"))),
            )
        show(state(track(sources = sources, evidence = EvidenceSet.of(mb, JvmHasher)))) {
            assertEquals(0, compose.onAllNodesWithText(text(R.string.home_unverified)).fetchSemanticsNodes().size)
            status(plural(R.plurals.home_sources_folded, 10))

            tap(FOLD_SHOW_TAG)
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
            tap(FOLD_SHOW_TAG)
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
            tap(FOLD_SHOW_TAG)
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
            tap(FOLD_SHOW_TAG)
            tap(FOLD_MORE_TAG)
            restore()
            assertEquals(16, linesShown())
        }

    @Test
    fun theFoldOutlivesAWhileWithNothingPlaying() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(FOLD_SHOW_TAG)
            current.value = NowPlayingState(PipelineView(), playing = null, settings = keep)
            compose.waitForIdle()
            current.value = state(long(20, playing = carefree), keep)
            compose.waitForIdle()
            assertEquals(Fold.PAGE, linesShown())
        }
    }

    @Test
    fun howMuchShowsIsAnnouncedOnlyOnceTheFoldWasChangedOnTheTrack() =
        show(state(long(20))) { current ->
            assertFalse(announced())
            // Sources landing change the count by themselves: not announced.
            current.value = state(long(21))
            compose.waitForIdle()
            assertFalse(announced())
            tap(FOLD_SHOW_TAG)
            assertTrue(announced())
            current.value = state(long(20, playing = carefree))
            compose.waitForIdle()
            assertFalse(announced())
        }

    @Test
    fun aCitationOfALineWhoseSourceAChipHidWhilePartlyUnfoldedReachesIt() =
        show(state(citing(long(20, wikipedia = 3), "W3"))) {
            tap(FOLD_SHOW_TAG)
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
            assertEquals(setOf("$FOLD_HIDE_TAG x2", FOLD_LESS_TAG), controls())
        }

    @Test
    fun aChipShowingItsLinesAgainWhilePartlyUnfoldedUnfoldsToThem() =
        show(state(long(20, wikipedia = 3))) {
            tap(FOLD_SHOW_TAG)
            tap(CHIP_PREFIX + "W")
            tap(CHIP_PREFIX + "W")
            // Unfolded to, and scrolled to: it is far below the chip tapped.
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            assertEquals(23, linesShown())
        }

    @Test
    fun withEveryEntryHiddenByTheChipsHideAndTheCountStay() =
        show(state(track())) {
            tap(FOLD_SHOW_TAG)
            tap(CHIP_PREFIX + "MB")
            tap(CHIP_PREFIX + "W")
            assertEquals(0, linesShown())
            assertEquals(setOf(FOLD_HIDE_TAG), controls())
            status(plural(R.plurals.home_sources_filtered, 3))
            tap(FOLD_HIDE_TAG)
            assertEquals(setOf(FOLD_SHOW_TAG), controls())
            status(plural(R.plurals.home_sources_filtered, 3))
            // *show* then shows the lines the chips hid, not an empty list.
            tap(FOLD_SHOW_TAG)
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
    fun aTrackChangeWhileTheSettingsAreUnreadKeepsTheFoldTheyKeep() {
        val keep = Settings(keepSourcesUnfolded = true)
        show(state(long(20), keep)) { current ->
            tap(FOLD_SHOW_TAG)
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
