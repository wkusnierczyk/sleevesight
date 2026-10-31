package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
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
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The *Dates* button under the album and its panel (#724): the sentences as they read, each date as
 * precisely as it is known; with none, why, and *Retry* where MusicBrainz could not be reached; the
 * button, shown for every track, opening the panel in its place and *Close* folding it back, a new
 * track closing it, the focus handed between them, and what a screen reader hears. Which sentences
 * are said is `HomeDatesTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class DatesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    // [sentences] as the panel says them, alone on the screen.
    private fun said(
        sentences: List<DateSentence>,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Column { DatesPanel(sentences) } } }
            block()
        }
    }

    /** The words of [line]'s text in italics. */
    private fun italics(line: String): List<String> {
        val text =
            compose
                .onNodeWithText(line)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        return text.spanStyles
            .filter { it.item.fontStyle == FontStyle.Italic }
            .map { text.text.substring(it.start, it.end) }
    }

    @Test
    fun eachSentenceReadsAsPickedTheReleasesTitleInItalics() {
        val hooked = "Hooked on a Feeling"
        val cases =
            listOf(
                DateSentence.Album("2014-07-29") to ("This album was released on 29 July 2014." to null),
                DateSentence.FirstHere to ("This track was first released on this album." to null),
                DateSentence.First("1994-08-23", "Grace", null) to
                    ("This track was first released on Grace on 23 August 1994." to "Grace"),
                DateSentence.First("1973", hooked, ReleaseKind.SINGLE) to
                    ("This track was first released on the single $hooked in 1973." to hooked),
                DateSentence.First("1983-05", "Four", ReleaseKind.EP) to
                    ("This track was first released on the EP Four in May 1983." to "Four"),
                DateSentence.First("1965-12-03", null, null) to
                    ("This track was first released on 3 December 1965." to null),
                DateSentence.Original("1984-06", "Leonard Cohen", "Various Positions", null) to
                    (
                        "The song was originally recorded in June 1984 by Leonard Cohen, " +
                            "and released on Various Positions." to "Various Positions"
                    ),
                DateSentence.Original("1968", "B. J. Thomas", hooked, ReleaseKind.SINGLE) to
                    (
                        "The song was originally recorded in 1968 by B. J. Thomas, " +
                            "and released on the single $hooked." to hooked
                    ),
                DateSentence.Original("1990", "Someone", "Four", ReleaseKind.EP) to
                    ("The song was originally recorded in 1990 by Someone, and released on the EP Four." to "Four"),
                DateSentence.Original("1993", "Nine Inch Nails", null, null) to
                    ("The song was originally recorded in 1993 by Nine Inch Nails." to null),
            )
        said(cases.map { it.first }) {
            for ((_, expected) in cases) {
                val (line, italic) = expected
                assertEquals(line, listOfNotNull(italic), italics(line))
            }
        }
    }

    @Test
    fun aDateReadsAsPreciselyAsItIsKnownTheDayWithNoLeadingZero() =
        said(
            listOf(
                DateSentence.First("2014-07-09", null, null),
                DateSentence.Album("2014-07"),
                DateSentence.Original("2014", "Someone", null, null),
            ),
        ) {
            compose.onNodeWithText("This track was first released on 9 July 2014.").assertExists()
            compose.onNodeWithText("This album was released in July 2014.").assertExists()
            compose.onNodeWithText("The song was originally recorded in 2014 by Someone.").assertExists()
        }

    // A track with its dates known: Sundown, its album's release, and its first release on it.
    private fun dated(playing: TrackState = track()): TrackState =
        playing.copy(
            resolution =
                Resolution(
                    firstRelease = FirstRelease("1974-01", "Sundown", "g-sundown", "r-sundown", "Album"),
                    releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "g-sundown")),
                ),
        )

    private fun state(
        track: TrackState,
        settings: Settings = Settings(),
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    // What Home shows: [show] starts it, and a test changes it as the pipeline would (a new track).
    private val current = mutableStateOf(state(dated()))

    // The screen's saved state, as an activity keeps it ([restore]), as `SourcesFoldScreenTest` does.
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

    // Where the screen's taps went: *Retry*'s, and the links to the settings.
    private val retried = mutableListOf<Triple<RunKey, SourceId, NowPlaying>>()
    private val opened = mutableListOf<SettingsTarget>()

    private val actions =
        NowPlayingActions(
            normalizer::normalize,
            openSettingsAt = { opened += it },
            askAgain = { key, source, shown -> retried += Triple(key, source, shown) },
        )

    private fun show(
        initial: NowPlayingState = state(dated()),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(current.value, actions)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun theButtonOpensThePanelInItsPlaceAndCloseFoldsItBack() =
        show {
            assertEquals(1, count(DATES_CHIP_TAG))
            assertEquals(0, count(DATES_PANEL_TAG))
            tap(DATES_CHIP_TAG)
            assertEquals(0, count(DATES_CHIP_TAG))
            assertEquals(1, count(DATES_PANEL_TAG))
            compose
                .onNodeWithTag(DATES_PANEL_HEADING_TAG)
                .assertTextEquals(text(R.string.home_dates))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose
                .onNodeWithTag(DATES_PANEL_CLOSE_TAG)
                .assertTextEquals(text(R.string.page_close))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            compose.onNodeWithText("This album was released in January 1974.").assertExists()
            compose.onNodeWithText("This track was first released on this album.").assertExists()

            tap(DATES_PANEL_CLOSE_TAG)
            assertEquals(1, count(DATES_CHIP_TAG))
            assertEquals(0, count(DATES_PANEL_TAG))
        }

    @Test
    fun aTitleTracksSingleOfTheAlbumsYearIsSaidAsTheSingleAndTheAlbumKeepsItsDate() {
        // "Like a Prayer": the single on 3 March 1989, the album on 21 March, one title.
        val prayer = sundown.copy(title = "Like a Prayer", artists = "Madonna", album = "Like a Prayer")
        val playing =
            track(prayer).copy(
                resolution =
                    Resolution(
                        firstRelease = FirstRelease("1989-03-03", "Like a Prayer", "g-single", "r-single", "Single"),
                        releaseDates =
                            listOf(
                                ReleaseDate("Like a Prayer", "1989-03-03", "g-single", "Single"),
                                ReleaseDate("Like a Prayer", "1989-03-21", "g-album", "Album"),
                            ),
                    ),
            )
        show(state(playing)) {
            tap(DATES_CHIP_TAG)
            compose.onNodeWithText("This album was released on 21 March 1989.").assertExists()
            val first = "This track was first released on the single Like a Prayer on 3 March 1989."
            compose.onNodeWithText(first).assertExists()
            assertEquals(0, compose.onAllNodesWithText("on this album", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aScreenReaderReadsTheButtonAsDatesAButtonCollapsedToShowDates() =
        show {
            assertEquals("Dates", text(R.string.home_dates))
            assertEquals("show dates", text(R.string.home_dates_show))
            compose
                .onNodeWithTag(DATES_CHIP_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_dates)),
                    ),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(
                    SemanticsMatcher("click labelled \"show dates\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_dates_show)
                    },
                ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
                .assertTouchHeightIsEqualTo(48.dp)
        }

    // [playing] with MusicBrainz's [musicBrainz] state, and no date known.
    private fun undated(
        musicBrainz: SourceState?,
        view: (TrackState) -> PipelineView = { PipelineView(it, aiReady = true) },
    ): NowPlayingState {
        val sources = musicBrainz?.let { mapOf(SourceId.MUSICBRAINZ to it) }.orEmpty()
        val playing = track(sources = sources)
        return state(playing).let { NowPlayingState(view(playing), it.playing, it.playback, settings = it.settings) }
    }

    // Opens the panel, and checks it holds [line] and no sentence.
    private fun opensTo(line: String) {
        assertEquals(1, count(DATES_CHIP_TAG))
        tap(DATES_CHIP_TAG)
        compose.onNodeWithText(line).assertExists()
        assertEquals(0, compose.onAllNodesWithText("released", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun withNoDateKnownTheButtonShowsAndThePanelSaysMusicBrainzGivesNone() =
        show(undated(done())) {
            assertEquals("MusicBrainz gives no dates for this song.", text(R.string.home_dates_none))
            opensTo(text(R.string.home_dates_none))
        }

    @Test
    fun whileMusicBrainzIsAskedThePanelSaysItIsLooking() {
        show(undated(SourceState.Pending)) {
            assertEquals("Looking on MusicBrainz…", text(R.string.home_musicbrainz_looking))
            opensTo(text(R.string.home_musicbrainz_looking))
        }
    }

    @Test
    fun beforeThePipelineReachesTheTrackThePanelSaysItIsLooking() =
        show(NowPlayingState(PipelineView(), sundown, PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING))) {
            opensTo(text(R.string.home_musicbrainz_looking))
        }

    @Test
    fun withMusicBrainzOffThePanelSaysSoAndLinksToTheSources() =
        show(undated(done(Outcome.SourceOff))) {
            assertEquals("MusicBrainz is disabled.", text(R.string.home_musicbrainz_off))
            opensTo(text(R.string.home_musicbrainz_off))
            // On its own line, the link's spaces unbroken.
            assertEquals("Settings\u00A0→\u00A0Sources", text(R.string.home_settings_sources_link))
            val see = app.getString(R.string.home_musicbrainz_off_see, text(R.string.home_settings_sources_link))
            assertEquals("See Settings\u00A0→\u00A0Sources.", see)
            compose.onNodeWithText(see).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.sources.linkTag
            }
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.sources), opened)
        }

    @Test
    fun whenMusicBrainzDidNotFindTheSongThePanelSaysSo() {
        assertEquals("MusicBrainz didn’t find this song.", text(R.string.home_musicbrainz_not_found))
        for (outcome in listOf(Outcome.None, Outcome.Rejected("“Sundown” by someone else"))) {
            show(undated(done(outcome))) { opensTo(text(R.string.home_musicbrainz_not_found)) }
        }
    }

    // Unreachable, *Retry* offered for the run under [key].
    private fun unreachable(offered: Boolean): NowPlayingState {
        val key = RunKey.of(normalizer, sundown)
        return undated(done(Outcome.Unreachable)) {
            PipelineView(
                it,
                aiReady = true,
                askAgainKey = key.takeIf { offered },
                askable = if (offered) setOf(SourceId.MUSICBRAINZ) else emptySet(),
            )
        }
    }

    @Test
    fun whenMusicBrainzCouldNotBeReachedRetryOnTheSameLineAsksItAgain() =
        show(unreachable(offered = true)) {
            assertEquals("MusicBrainz could not be reached.", text(R.string.home_musicbrainz_unreachable))
            opensTo(text(R.string.home_musicbrainz_unreachable))
            compose
                .onNodeWithTag(RETRY_TAG)
                .performScrollTo()
                .assertTextEquals(text(R.string.home_retry))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(
                    SemanticsMatcher("click labelled \"ask MusicBrainz again\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == "ask MusicBrainz again"
                    },
                ).assertTouchHeightIsEqualTo(48.dp)
            // Beside the message, centred on it.
            val message = compose.onNodeWithText(text(R.string.home_musicbrainz_unreachable)).fetchSemanticsNode()
            val retry = compose.onNodeWithTag(RETRY_TAG).fetchSemanticsNode()
            assertTrue(retry.boundsInRoot.left >= message.boundsInRoot.right)
            assertEquals(message.boundsInRoot.center.y, retry.boundsInRoot.center.y, 1f)
            tap(RETRY_TAG)
            assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), SourceId.MUSICBRAINZ, sundown)), retried)
        }

    @Test
    fun aTapOnRetryHandsTheFocusToCloseAsTheLineGivesWayToLooking() =
        show(unreachable(offered = true)) {
            tap(DATES_CHIP_TAG)
            compose.onNodeWithTag(RETRY_TAG).requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(RETRY_TAG).assertIsFocused()
            tap(RETRY_TAG)
            // The pipeline asks MusicBrainz again: the line gives way to "Looking on MusicBrainz…".
            current.value = undated(SourceState.Pending)
            compose.waitForIdle()
            assertEquals(0, count(RETRY_TAG))
            compose.onNodeWithText(text(R.string.home_musicbrainz_looking)).assertExists()
            compose.onNodeWithTag(DATES_PANEL_CLOSE_TAG).assertIsFocused()
        }

    @Test
    fun whenRetryIsNotOfferedTheMessageStandsAlone() =
        show(unreachable(offered = false)) {
            opensTo(text(R.string.home_musicbrainz_unreachable))
            assertEquals(0, count(RETRY_TAG))
        }

    @Test
    fun aNewTrackClosesThePanel() =
        show {
            tap(DATES_CHIP_TAG)
            assertEquals(1, count(DATES_PANEL_TAG))
            current.value = state(dated(track(carefree)))
            compose.waitForIdle()
            assertEquals(0, count(DATES_PANEL_TAG))
            assertEquals(1, count(DATES_CHIP_TAG))
            // And the track it was opened on, back, keeps it closed: opened on one track only.
            current.value = state(dated())
            compose.waitForIdle()
            assertEquals(0, count(DATES_PANEL_TAG))
        }

    @Test
    fun thePanelStaysOpenAcrossARecreatedScreen() =
        show {
            tap(DATES_CHIP_TAG)
            restore()
            assertEquals(1, count(DATES_PANEL_TAG))
            assertEquals(0, count(DATES_CHIP_TAG))
        }

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheButtonOnClosing() =
        show {
            tap(DATES_CHIP_TAG)
            compose.onNodeWithTag(DATES_PANEL_CLOSE_TAG).assertIsFocused()
            tap(DATES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(DATES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun aNewTrackClosingThePanelWithTheFocusInItHandsTheFocusToTheButton() =
        show {
            tap(DATES_CHIP_TAG)
            compose.onNodeWithTag(DATES_PANEL_CLOSE_TAG).assertIsFocused()
            current.value = state(dated(track(carefree)))
            compose.waitForIdle()
            compose.onNodeWithTag(DATES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun underSeparatePagesTheButtonStillOpensInPlace() =
        show(state(dated(), Settings(layout = Layout.SEPARATE_PAGES))) {
            tap(DATES_CHIP_TAG)
            assertEquals(1, count(DATES_PANEL_TAG))
        }

    @Test
    fun theAlbumLineKeepsItsYearAndTheFirstReleaseIsNotUnderIt() =
        show {
            compose.onNodeWithText("${sundown.album}, 1974").assertExists()
            assertEquals(0, compose.onAllNodesWithText("First released", substring = true).fetchSemanticsNodes().size)
        }
}
