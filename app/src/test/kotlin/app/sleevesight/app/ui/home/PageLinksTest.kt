package app.sleevesight.app.ui.home

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings.Secure
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
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
import app.sleevesight.app.ui.SleevesightNavHost
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
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
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The ways to Home's pages under *Layout*'s *Separate pages* (#548), on a phone's screen, with Home
 * and its pages drawn as the app draws them, from a [HomeFeed]: *Genres* opens the genres' page
 * (#558, #772), and back returns to Home where it was scrolled to; with one level of two artists'
 * labels, a genre they share has one panel there, with a row per artist. A link acts while its screen is
 * the top of the back stack, whatever that screen's lifecycle says: after a back swipe started and
 * cancelled, which leaves the page short of resumed, and while Home comes back from a page; and a
 * double tap still opens one page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class PageLinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun label(
        tag: String,
        level: GenreLevel,
        artist: String? = null,
    ): TrackLabel {
        val shown = tag.replaceFirstChar(Char::uppercase)
        val genre = GenreLabel(tag, "family", shown, tag, level = level, artist = artist)
        // A track's label with the votes to lead the folded chips (#799), as the tests' chips are the track's.
        val votes = TrackGenres.MIN_VOTES.takeIf { level == GenreLevel.TRACK }
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")), votes)
    }

    // Two levels: Home shows the track's chips, the page the table of both.
    private val genres =
        TrackGenres(
            listOf(
                GenreRow(GenreLevel.TRACK, null, listOf(label("folk", GenreLevel.TRACK))),
                GenreRow(
                    GenreLevel.ARTIST,
                    "Gordon Lightfoot",
                    listOf(label("rock", GenreLevel.ARTIST, "Gordon Lightfoot")),
                ),
            ),
        )

    // *Genres* on Home, which opens the genres' page (#772).
    private val link = GENRES_PAGE_LINK_TAG

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

    /** The app's navigation graph under *Separate pages*, Sundown playing, with 20 lines and [genres]. */
    private fun show(
        genres: TrackGenres = this.genres,
        block: () -> Unit,
    ) {
        val lines = (1..20).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val playing =
            track(
                sundown,
                InsightState.Idle(canWrite = false),
                mapOf(SourceId.MUSICBRAINZ to done(evidence = lines)),
                EvidenceSet.of(lines, JvmHasher),
            ).copy(genres = genres)
        val key = RunKey.of(normalizer, sundown)
        val feed =
            HomeFeed(
                view = MutableStateFlow(PipelineView(playing, aiReady = true, writeKey = key)),
                nowPlaying = MutableStateFlow(sundown),
                playback = MutableStateFlow(PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)),
                art = MutableStateFlow(null),
                settings = MutableStateFlow(Settings(layout = Layout.SEPARATE_PAGES)),
                normalizer = normalizer,
                playerLabel = { it },
                playerActivity = { null },
                write = { _, _ -> },
                stop = {},
            )
        this.feed = feed
        ActivityScenario.launch(MainActivity::class.java).use { launched ->
            scenario = launched
            drawGraph(restored = null)
            block()
        }
    }

    private lateinit var feed: HomeFeed

    // The navigation graph drawn from [feed], its controller new, restored from [restored] if given.
    private fun drawGraph(restored: Bundle?) {
        scenario.onActivity { activity ->
            val graph = (activity.application as SleevesightApplication).graph
            activity.setContent {
                nav = rememberNavController().apply { restored?.let(::restoreState) }
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
    }

    private fun route() = nav.currentDestination?.route

    // Each destination's route, as the navigation graph names it.
    private val home = Home.serializer().descriptor.serialName
    private val genresPage = serializer<HomePage.Genres>().descriptor.serialName

    private fun onHome() = assertEquals(home, route())

    // The genres' page, as *Genres* opens it ([HomePage.Genres]) or a saved back stack had it ([HomePage.GenresAt]).
    private fun onGenres() = assertTrue("on ${route()}", route()?.startsWith(genresPage) == true)

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun open(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun backArrow() {
        compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
        compose.waitForIdle()
    }

    // A back swipe from the screen's edge, started and drawn halfway, then let go of: cancelled.
    private fun cancelledBackSwipe() {
        scenario.onActivity {
            val back = it.onBackPressedDispatcher
            back.dispatchOnBackStarted(BackEventCompat(0f, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(SWIPE_X, EDGE_Y, 0.5f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
    }

    // A shorter screen, so Home scrolls under *Separate pages*.
    @Test
    @Config(qualifiers = "w411dp-h480dp-420dpi")
    fun genresOpensTheGenresPageAndBackReturnsToHomeWhereItWas() =
        show {
            val unscrolled = top(link)
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo()
            val scrolled = top(link)
            assertNotEquals(unscrolled, scrolled)

            compose.onNodeWithTag(link).performClick()
            compose.waitForIdle()
            onGenres()
            assertEquals(genresPage, route())
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
            compose
                .onNodeWithTag(
                    GENRES_ROW_PREFIX + "1",
                ).assert(hasText(app.getString(R.string.home_genres_artist_row)))
            backArrow()
            onHome()
            assertEquals(scrolled, top(link))

            compose.onNodeWithTag(link).performClick()
            compose.waitForIdle()
            onGenres()
            // Android's back, as the system sends it.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            onHome()
            assertEquals(scrolled, top(link))
        }

    // The genres' page as 0.1.5 saved it on a back stack: the object `HomePage.Genres`, whose
    // destination id is the hash of its serial name and whose route is that name alone. Written out
    // here, not read from the code under test, so a change to the object fails this test.
    @Test
    fun theGenresRouteOfEarlierVersionsIsKeptSoASavedBackStackRestores() =
        show {
            val name = "app.sleevesight.app.ui.home.HomePage.Genres"
            scenario.onActivity { nav.navigate(HomePage.Genres) }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals(GENRES_PAGE_ID, nav.currentDestination?.id)
                assertEquals(name.hashCode(), nav.currentDestination?.id)
                assertEquals(name, nav.currentDestination?.route)
            }
            // Every genre's panel (#772): here, the one.
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")

            // A back stack saved with the page on it is restored into a new controller.
            lateinit var saved: Bundle
            compose.runOnUiThread { saved = nav.saveState()!! }
            drawGraph(restored = saved)
            compose.runOnUiThread { assertEquals(name, nav.currentDestination?.route) }
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Folk")
            backArrow()
            onHome()
        }

    @Test
    fun afterABackSwipeStartedAndCancelledThePagesBackArrowStillReturnsHome() =
        show {
            open(link)
            onGenres()
            cancelledBackSwipe()
            // Still on the page, and the page is on top of the stack, though not resumed: what a
            // check of the lifecycle refused.
            onGenres()
            assertNotEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry?.lifecycle?.currentState)
            backArrow()
            onHome()
            // And Home's links work after it.
            open(link)
            onGenres()
        }

    // An artist's row of [tags].
    private fun artistRow(
        artist: String,
        vararg tags: String,
    ) = GenreRow(GenreLevel.ARTIST, artist, tags.map { label(it, GenreLevel.ARTIST, artist) })

    @Test
    fun withOneLevelOfTwoArtistsAGenreTheyShareHasOnePanelWithARowPerArtist() =
        show(TrackGenres(listOf(artistRow("Ike Turner", "soul"), artistRow("Tina Turner", "soul", "funk")))) {
            open(link)
            onGenres()
            // The shared genre once, in the chips' order: a panel each for soul and funk.
            val headings = compose.onAllNodesWithTag(GENRE_PANEL_HEADING_TAG)
            headings[0].assertTextEquals("Soul")
            headings[1].assertTextEquals("Funk")
            assertEquals(2, headings.fetchSemanticsNodes().size)
            // In soul's, a row per artist, each with its own label of the genre, both marked.
            // Their sources name them (#786), the rows' tags as each gave them.
            compose.onAllNodesWithTag(GENRES_ROW_PREFIX + "0")[0].assert(hasText("Soul")).assert(hasText("Funk").not())
            compose.onAllNodesWithTag(GENRES_ROW_PREFIX + "1")[0].assert(hasText("Funk"))
            assertEquals("Artist, Soul, selected", compose.onAllNodesWithTag(GENRES_ROW_PREFIX + "0")[0].reading())
            assertEquals(
                "Artist, Soul, selected, Funk",
                compose.onAllNodesWithTag(GENRES_ROW_PREFIX + "1")[0].reading(),
            )
            compose
                .onAllNodesWithTag(
                    GENRE_CELL_PREFIX + "artist:Tina Turner:funk",
                    useUnmergedTree = true,
                )[0]
                .assertExists()
        }

    @Test
    fun aTapOnHomeWhileItComesBackFromAPageOpensThePage() =
        show {
            open(link)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            onHome()
            // Home is on top again but still coming in, short of resumed, when the tap lands.
            assertNotEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry?.lifecycle?.currentState)
            // Home's *Genres*, drawn as the page leaves.
            compose.onNodeWithTag(link).performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            onGenres()
        }

    @Test
    fun aDoubleTapOnGenresOpensOnePageAndADoubleTapOnBackReturnsHomeOnce() =
        show {
            // Both taps land before the page comes in, as a quick double tap does.
            compose.onNodeWithTag(link).performScrollTo().performTouchInput {
                click()
                click()
            }
            compose.waitForIdle()
            onGenres()
            assertEquals(home, nav.previousBackStackEntry?.destination?.route)
            compose.onNodeWithTag(PAGE_BACK_TAG).performTouchInput {
                click()
                click()
            }
            compose.waitForIdle()
            onHome()
            compose.onNodeWithTag(link).assertExists()
        }
}

// Where the test's back swipe starts, down the screen's edge, and how far in it is drawn, in pixels.
private const val EDGE_Y = 900f
private const val SWIPE_X = 300f

// The destination id 0.1.5 gave the genres' page: the hash of `HomePage.Genres`'s serial name.
private const val GENRES_PAGE_ID = -1366372184
