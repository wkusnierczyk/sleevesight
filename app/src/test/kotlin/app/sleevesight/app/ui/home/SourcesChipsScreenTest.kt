package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What *Sources* shows unfolded (#440): a chip for each source that gave lines, by name; a note only
 * for a key or a failure; and under *Show grounding details* every source's status, the `M` chip and
 * the rejected matches, as before.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesChipsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private var settingsOpened = 0

    // The model's own knowledge allowed, so there is an `M` chip to leave out or show.
    private val modelOn = Settings(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)
    private val details = modelOn.copy(showGroundingDetails = true)

    /**
     * Sundown's lines from MusicBrainz and Wikipedia; web search's match rejected, Discogs off, and
     * Last.fm's key not saved.
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
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun unfold() {
        compose.onNodeWithTag(SOURCES_FOLD_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun countText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun named(
        tag: String,
        name: String,
    ) = compose
        .onNodeWithTag(CHIP_PREFIX + tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    @Test
    fun theChipsAreTheSourcesThatGaveLinesByNameAndNoStatusShows() =
        show(mixed) {
            unfold()
            named("MB", text(R.string.settings_source_musicbrainz))
            named("W", text(R.string.home_source_chip_wikipedia))
            // Rejected, off, needing a key, or the model's own knowledge: no chip.
            for (tag in listOf("G", "D", "L", "M")) assertEquals(tag, 0, count(CHIP_PREFIX + tag))
            // No status in words: kept, rejected, off.
            val kept = text(R.string.home_source_cached, text(R.string.home_source_found_song))
            assertEquals(0, countText(text(R.string.home_source_chip, "MB", kept)))
            assertEquals(0, countText(text(R.string.home_source_chip, "G", text(R.string.home_source_rejected))))
            // A chip filters its lines, as before, and its click is named by the source.
            assertEquals(
                text(R.string.home_source_filter, text(R.string.home_source_chip_wikipedia)),
                compose
                    .onNodeWithTag(CHIP_PREFIX + "W")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .label,
            )
            compose.onNodeWithTag(CHIP_PREFIX + "W").performScrollTo().performClick()
            assertEquals(0, count(LINE_PREFIX + "W1"))
            assertEquals(0, count(LINE_PREFIX + "WD1"))
            // No rejected matches without the setting.
            assertEquals(0, countText(text(R.string.home_rejected_title)))
        }

    @Test
    fun thePlayersReleaseChipIsNamedPlayer() {
        val release = draft(SourceId.PLAYER, "On the release \"Sundown\".")
        val evidence = EvidenceSet.of(listOf(musicBrainzLine, release), JvmHasher)
        val sources = mapOf(SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)))
        show(track(sources = sources, evidence = evidence)) {
            unfold()
            named("P", text(R.string.home_source_chip_player))
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
        }
        show(mixed, needsKey = emptySet()) {
            unfold()
            assertEquals(0, count(SOURCES_NOTE_TAG))
        }
    }

    @Test
    fun groundingDetailsBringBackEveryStatusTheModelsChipAndTheRejectedMatches() =
        show(mixed, settings = details) {
            unfold()
            val kept = text(R.string.home_source_cached, text(R.string.home_source_found_song))
            compose.onNodeWithText(text(R.string.home_source_chip, "MB", kept)).assertExists()
            compose
                .onNodeWithText(text(R.string.home_source_chip, "G", text(R.string.home_source_rejected)))
                .assertExists()
            compose.onNodeWithText(text(R.string.home_source_chip, "D", text(R.string.home_source_off))).assertExists()
            compose
                .onNodeWithText(text(R.string.home_source_chip, "L", text(R.string.home_source_needs_key)))
                .assertExists()
            compose
                .onNodeWithText(text(R.string.home_source_chip, "M", text(R.string.home_source_model_allowed)))
                .assertExists()
            compose.onNodeWithText(text(R.string.home_rejected_title)).performScrollTo().assertExists()
            val web = text(R.string.home_source_name_web)
            compose.onNodeWithText(text(R.string.home_rejected, web, "another song")).assertExists()
            // The note of the key stays, with its link.
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertExists()
        }

    @Test
    fun groundingDetailsStillShowNothingButTheHeadingWhileFolded() =
        show(mixed, settings = details) {
            for (tag in listOf("MB", "W", "G", "L", "M")) assertEquals(tag, 0, count(CHIP_PREFIX + tag))
            assertEquals(0, countText(text(R.string.home_rejected_title)))
            assertEquals(0, count(SOURCES_NOTE_TAG))
        }
}
