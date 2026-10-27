package app.sleevesight.app.ui.home

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.ui.Home
import app.sleevesight.app.ui.HomeLinks
import app.sleevesight.app.ui.SleevesightNavHost
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * *Layout* (#447), through the app's navigation graph: under *Expandable boxes* each item of Home
 * expands in place; under *Separate pages* *Sources* and the genres (from a genre chip, #558) each
 * open on a page of their own, with a back arrow and a title a screen reader reads as a heading, and back returns to
 * Home where it was scrolled to. The insight shows in full on Home either way.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutPagesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val pages = Settings(layout = Layout.SEPARATE_PAGES)
    private val boxes = Settings(layout = Layout.EXPANDABLE_BOXES)

    private val rock = label("rock", GenreLevel.ARTIST, "Gordon Lightfoot")
    private val genres =
        TrackGenres(
            listOf(
                GenreRow(GenreLevel.TRACK, null, listOf(label("folk pop", GenreLevel.TRACK))),
                GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(rock)),
            ),
        )

    private fun label(
        tag: String,
        level: GenreLevel,
        artist: String? = null,
    ): TrackLabel {
        val shown = tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val genre = GenreLabel(tag, "family", shown, tag.replace(" ", ""), level = level, artist = artist)
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")))
    }

    /** [playing] (Sundown) with [count] MusicBrainz lines, `[fact] 1.` on, its genres, and [insight]. */
    private fun long(
        count: Int = 20,
        insight: InsightState = InsightState.Idle(canWrite = false),
        playing: NowPlaying = sundown,
        fact: String = "Fact",
    ): TrackState {
        val mb = (1..count).map { draft(SourceId.MUSICBRAINZ, "$fact $it.") }
        return track(
            playing,
            insight,
            mapOf(SourceId.MUSICBRAINZ to done(evidence = mb)),
            EvidenceSet.of(mb, JvmHasher),
        ).copy(genres = genres)
    }

    private fun state(
        track: TrackState,
        settings: Settings,
    ) = NowPlayingState(
        PipelineView(track, aiReady = true, writeKey = RunKey.of(normalizer, sundown)),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    private var writes = 0

    private fun actions(links: HomeLinks) =
        NowPlayingActions(
            normalizer::normalize,
            write = { _, _ -> writes++ },
            openSettings = links.openSettings,
            openPage = links.openPage,
        )

    @Before
    fun firstRunDone() {
        // As after the first run, with notification access on: the app opens on Home.
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(
            app.getSystemService(NotificationManager::class.java),
        ).setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
    }

    private lateinit var nav: NavHostController
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** The app's navigation graph, Home and its pages drawn from [initial], as it changes. */
    private fun show(
        initial: NowPlayingState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { launched ->
            scenario = launched
            launched.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        nav,
                        pipeline = { links -> NowPlayingContent(current.value, actions(links)) },
                        page = {
                            page,
                            links,
                            ->
                            NowPlayingPageContent(page, current.value, actions(links), links.back)
                        },
                    )
                }
            }
            compose.waitForIdle()
            block(current)
        }
    }

    /**
     * As [show], but Home and its pages drawn as the app draws them, by [NowPlayingHome] and
     * [NowPlayingPage] from a [HomeFeed] of [track], with the settings published as the app
     * publishes them, a [StateFlow] held for the process ([settings]).
     */
    private fun showFeed(
        track: TrackState,
        settings: Settings,
        block: () -> Unit,
    ) {
        val playing = track.nowPlaying
        val feed =
            HomeFeed(
                view = MutableStateFlow(PipelineView(track, aiReady = true, writeKey = RunKey.of(normalizer, playing))),
                nowPlaying = MutableStateFlow(playing),
                playback = MutableStateFlow(PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)),
                art = MutableStateFlow(null),
                settings = MutableStateFlow(settings),
                normalizer = normalizer,
                playerLabel = { it },
                playerActivity = { null },
                write = { _, _ -> writes++ },
                stop = {},
            )
        ActivityScenario.launch(MainActivity::class.java).use { launched ->
            scenario = launched
            launched.onActivity { activity ->
                val graph = (activity.application as SleevesightApplication).graph
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        nav,
                        pipeline = { links -> NowPlayingHome(feed, links.openSettings, links.openPage) },
                        page = { page, links ->
                            NowPlayingPage(feed, page, links.openSettings, links.openPage, links.back)
                        },
                    )
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun onHome() = assertEquals(Home.serializer().descriptor.serialName, nav.currentDestination?.route)

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    // A page's title: drawn, and read as a heading.
    private fun title(expected: String) =
        compose
            .onNodeWithTag(PAGE_TITLE_TAG)
            .assertTextEquals(expected)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    private fun back() {
        compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
        compose.waitForIdle()
    }

    private fun clickLabelled(label: String) =
        SemanticsMatcher("click labelled \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun underExpandableBoxesEachItemExpandsInPlaceOnHome() =
        show(state(long(), boxes)) {
            assertEquals(0, count(SOURCES_PAGE_LINK_TAG))

            compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertExists()
            // A genre chip opens its panel on Home (#558), with the table and where its labels come from.
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").performScrollTo().performClick()
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk Pop")
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "artist:Gordon Lightfoot:rock").assertExists()
            compose.onNodeWithText(text(R.string.home_genre_from_track), substring = true).assertExists()
            onHome()
        }

    @Test
    fun underSeparatePagesSourcesOpensAPageAndBackReturnsToHomeWhereItWas() =
        show(state(long(), pages)) {
            assertEquals(0, count(SOURCES_CHIP_TAG))
            assertEquals(0, count(LINE_PREFIX + "MB1"))
            val unscrolled = top(SOURCES_PAGE_LINK_TAG)
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo()
            val scrolled = top(SOURCES_PAGE_LINK_TAG)
            assertNotEquals(unscrolled, scrolled)

            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performClick()
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            compose.onNodeWithContentDescription(text(R.string.page_back)).assertIsDisplayed()
            // The list at its first screenful, with its paging.
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(text(R.string.home_sources_showing, Fold.PAGE, 20))
            compose.onNodeWithTag(FOLD_MORE_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(LINE_PREFIX + "MB16").assertExists()

            back()
            onHome()
            assertEquals(scrolled, top(SOURCES_PAGE_LINK_TAG))

            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performClick()
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            pressBack()
            onHome()
            assertEquals(scrolled, top(SOURCES_PAGE_LINK_TAG))
        }

    @Test
    fun throughTheAppsFeedHomeComesBackFromAPageLaidOutAndScrolledAsItWasLeft() =
        showFeed(long(), pages) {
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo()
            val scrolled = top(SOURCES_PAGE_LINK_TAG)

            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performClick()
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            // Frame by frame as Home comes back: laid out under *Separate pages* from its first
            // frame, never as boxes while the settings are still being read.
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
            var drawn = false
            repeat(BACK_FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals(0, count(SOURCES_CHIP_TAG))
                drawn = drawn || count(SOURCES_PAGE_LINK_TAG) > 0
            }
            // Home was drawn in those frames, so the fold's absence means something.
            assertTrue(drawn)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            onHome()
            assertEquals(scrolled, top(SOURCES_PAGE_LINK_TAG))
        }

    @Test
    fun untilTheSettingsAreReadHomeDrawsNeitherItemThenThePagesLayout() {
        val read = state(long(), pages)
        // As on a cold start, before the stored settings' first read.
        show(NowPlayingState(read.view, read.playing, read.playback, settings = null)) { current ->
            // Home itself is drawn: the insight shows, with no settings needed.
            compose.onNodeWithText(text(R.string.home_insight_title)).assertExists()
            assertEquals(0, count(SOURCES_CHIP_TAG))
            assertEquals(0, count(SOURCES_PAGE_LINK_TAG))
            assertEquals(0, count(GENRE_PREFIX + "track:folkpop"))

            // The settings' first read, frame by frame: the pages' layout, never the boxes'.
            compose.mainClock.autoAdvance = false
            current.value = read
            var drawn = false
            repeat(BACK_FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                assertEquals(0, count(SOURCES_CHIP_TAG))
                drawn = drawn || count(SOURCES_PAGE_LINK_TAG) > 0
            }
            assertTrue(drawn)
            compose.mainClock.autoAdvance = true
            // A genre chip opens the genres' page.
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .assert(clickLabelled(text(R.string.home_page_open)))
        }
    }

    @Test
    fun aDoubleTapOpensOnePageAndOneBackReturnsHome() =
        show(state(long(), pages)) {
            // Both taps land before the page comes in, as a quick double tap does.
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo().performTouchInput {
                click()
                click()
            }
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            back()
            onHome()
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).assertExists()
        }

    // Android's back, as the system sends it.
    private fun pressBack() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aCitationOpensSourcesPageAtItsLine() =
        show(state(long(insight = written("It was a hit [MB12].")), pages)) {
            compose.onNodeWithText("It was a hit", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "MB12"
            }
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            // Unfolded by whole screenfuls as far as the line, and scrolled to it.
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(text(R.string.home_sources_showing, 16, 20))
            compose.onNodeWithTag(LINE_PREFIX + "MB12").assertIsDisplayed()
        }

    @Test
    fun aChipOpensTheGenresPageAtItsGenreWithTheChipsAndThePanel() =
        show(state(long(), pages)) {
            // On Home, the chips alone: no `Genres ›`, no panel.
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").assertExists()
            assertEquals(0, count(GENRE_PREFIX + "artist:Gordon Lightfoot:rock"))
            assertEquals(0, count(GENRE_PANEL_TAG))
            val titles = compose.onAllNodesWithText(text(R.string.home_genres), substring = true)
            assertEquals(0, titles.fetchSemanticsNodes().size)

            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").performScrollTo().performClick()
            compose.waitForIdle()
            title(text(R.string.home_genres))
            // The chips, the tapped one marked, and its panel, with no *Close*: the back arrow closes the page.
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").assertIsSelected()
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk Pop")
            assertEquals(0, count(GENRE_PANEL_CLOSE_TAG))
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").assertTextEquals("Gordon Lightfoot")
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "track:folkpop").assertIsSelected()
            // The table's labels are plain: nothing opens from them.
            compose
                .onNodeWithTag(GENRE_CELL_PREFIX + "artist:Gordon Lightfoot:rock")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            // The marked chip has nothing to do on the page: not a button, so a screen reader offers no action.
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

            back()
            onHome()
            assertEquals(0, count(GENRE_PANEL_TAG))
        }

    @Test
    fun withOneLevelEachChipOpensThePageWithTheTablesOneRow() =
        show(state(long().copy(genres = TrackGenres(genres.rows.take(1))), pages)) {
            // A button a screen reader names "open page", never selected, as with more levels (#548).
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .performScrollTo()
                .assert(clickLabelled(text(R.string.home_page_open)))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .performClick()
            compose.waitForIdle()
            title(text(R.string.home_genres))
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").assertExists()
            assertEquals(0, count(GENRES_ROW_PREFIX + "1"))
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "track:folkpop").assertIsSelected()

            back()
            onHome()
        }

    @Test
    fun theInsightShowsInFullOnHomeUnderEitherLayoutAndOpensNoPage() {
        for (layout in listOf(boxes, pages)) {
            show(state(long(insight = InsightState.Idle(canWrite = true)), layout)) {
                compose
                    .onNodeWithText(text(R.string.home_insight_title))
                    .performScrollTo()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                compose.onNodeWithTag(WRITE_TAG).performScrollTo().performClick()
                onHome()
            }
        }
        assertEquals(2, writes)
    }

    @Test
    fun sourcesPageFollowsANewTrackFromItsFirstScreenful() =
        show(state(long(), pages)) { current ->
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(FOLD_MORE_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(text(R.string.home_sources_showing, 16, 20))

            current.value = state(long(count = 12, playing = carefree, fact = "Highway"), pages)
            compose.waitForIdle()
            title(text(R.string.home_sources_title))
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(text(R.string.home_sources_showing, Fold.PAGE, 12))
            compose.onNodeWithText("Highway 1.", substring = true).assertExists()
            assertEquals(0, compose.onAllNodesWithText("Fact 1.", substring = true).fetchSemanticsNodes().size)
        }

    @Test
    fun genresPageFollowsANewTrackAOneLevelTrackAndShowGenres() =
        show(state(long(), pages)) { current ->
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "artist:Gordon Lightfoot:rock").assertExists()

            // Another track, with genres of its own.
            val jazz =
                TrackGenres(
                    listOf(
                        GenreRow(GenreLevel.TRACK, null, listOf(label("jazz", GenreLevel.TRACK))),
                        GenreRow(GenreLevel.ALBUM, null, listOf(label("bebop", GenreLevel.ALBUM))),
                    ),
                )
            val next = long(playing = carefree)
            current.value = state(next.copy(genres = jazz), pages)
            compose.waitForIdle()
            title(text(R.string.home_genres))
            // The genre tapped on Home is not this track's: the first chip's panel shows.
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Jazz")
            compose.onNodeWithTag(GENRE_CELL_PREFIX + "album:bebop").assertExists()
            assertEquals(0, count(GENRE_CELL_PREFIX + "artist:Gordon Lightfoot:rock"))

            // One level: the table's one row, its labels plain.
            current.value = state(next.copy(genres = TrackGenres(jazz.rows.take(1))), pages)
            compose.waitForIdle()
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").assertExists()
            assertEquals(0, count(GENRES_ROW_PREFIX + "1"))
            compose
                .onNodeWithTag(GENRE_CELL_PREFIX + "track:jazz")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

            // *Show genres* turned off.
            current.value = state(next.copy(genres = jazz), pages.copy(showGenres = false))
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.home_genres_hidden)).assertIsDisplayed()
            assertEquals(0, count(GENRE_PREFIX + "track:jazz"))
        }

    @Test
    fun eachPageOpensFreshWhateverKeepSourcesSays() =
        show(state(long(), pages.copy(keepSourcesUnfolded = true))) {
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(FOLD_ALL_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(
                app.resources.getQuantityString(R.plurals.home_sources_showing_all, 20, 20),
            )
            back()
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(FOLD_STATUS_TAG).assertTextEquals(text(R.string.home_sources_showing, Fold.PAGE, 20))
        }

    @Test
    fun aScreenReaderReadsEachWayToAPageAsItsNameAndItsActionAsOpenPage() =
        show(state(long(), pages)) {
            val open = text(R.string.home_page_open)
            compose
                .onNodeWithTag(SOURCES_PAGE_LINK_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_sources_title)),
                    ),
                ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(clickLabelled(open))
                // A chip since #623, as the genres' are; it opens a page, so it is never collapsed.
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assertTouchHeightIsEqualTo(48.dp)
            // A chip opens the genres' page: a button, never selected.
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .performScrollTo()
                .assertTouchHeightIsEqualTo(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(clickLabelled(open))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        }
}

// Frames enough for back's transition to Home to finish.
private const val BACK_FRAMES = 60
