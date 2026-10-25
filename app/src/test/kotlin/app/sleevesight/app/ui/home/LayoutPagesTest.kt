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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * *Layout* (#447), through the app's navigation graph: under *Expandable boxes* each item of Home
 * expands in place; under *Separate pages* *Sources* and the genres' table each open on a page of
 * their own, with a back arrow and a title a screen reader reads as a heading, and back returns to
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

    /** Sundown with [count] MusicBrainz lines, its genres, and [insight]. */
    private fun long(
        count: Int = 20,
        insight: InsightState = InsightState.Idle(canWrite = false),
    ): TrackState {
        val mb = (1..count).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        return track(
            sundown,
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
            assertEquals(0, count(GENRES_PAGE_LINK_TAG))

            compose.onNodeWithTag(SOURCES_FOLD_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertExists()
            compose.onNodeWithTag(GENRES_FOLD_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GENRE_PREFIX + "artist:Gordon Lightfoot:rock").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_genre_from, "Rock")).assertExists()
            onHome()
        }

    @Test
    fun underSeparatePagesSourcesOpensAPageAndBackReturnsToHomeWhereItWas() =
        show(state(long(), pages)) {
            assertEquals(0, count(SOURCES_FOLD_TAG))
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
    fun genresAndEachChipOpenTheTableOnItsPageWherePlainLabelsShow() =
        show(state(long(), pages)) {
            assertEquals(0, count(GENRES_FOLD_TAG))
            // Folded on Home: the track's chips after the way to the table.
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").assertExists()
            assertEquals(0, count(GENRE_PREFIX + "artist:Gordon Lightfoot:rock"))

            compose.onNodeWithTag(GENRES_PAGE_LINK_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            title(text(R.string.home_genres))
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").assertExists()
            // On the page, the labels are plain: nothing opens from them.
            compose
                .onNodeWithTag(GENRE_PREFIX + "artist:Gordon Lightfoot:rock")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

            back()
            onHome()

            // A chip on Home opens the table's page too.
            compose.onNodeWithTag(GENRE_PREFIX + "track:folkpop").performScrollTo().performClick()
            compose.waitForIdle()
            title(text(R.string.home_genres))
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").assertExists()
        }

    @Test
    fun withOneLevelTheChipsAreAllThereIsAndOpenNothing() =
        show(state(long().copy(genres = TrackGenres(genres.rows.take(1))), pages)) {
            assertEquals(0, count(GENRES_PAGE_LINK_TAG))
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .performScrollTo()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
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
                .assertTouchHeightIsEqualTo(48.dp)
            compose
                .onNodeWithTag(GENRES_PAGE_LINK_TAG)
                .performScrollTo()
                .assertTouchHeightIsEqualTo(48.dp)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_genres)),
                    ),
                ).assert(clickLabelled(open))
            // A chip opens the table's page: a button, never selected.
            compose
                .onNodeWithTag(GENRE_PREFIX + "track:folkpop")
                .assert(clickLabelled(open))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        }
}
