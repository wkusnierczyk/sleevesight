package app.sleevesight.app.ui.home

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The ways to Home's pages under *Layout*'s *Separate pages* (#548), on a phone's screen, with Home
 * and its pages drawn as the app draws them, from a [HomeFeed]: *Genres ›* and a genre chip open the
 * genres' page, and back returns to Home where it was scrolled to. A link acts while its screen is
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
        val genre = GenreLabel(tag, "family", tag.replaceFirstChar(Char::uppercase), tag, level = level, artist = artist)
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")))
    }

    // Two levels, so Home shows `Genres ›` before the track's chips.
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

    private val folk = GENRE_PREFIX + "track:folk"

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

    /** The app's navigation graph under *Separate pages*, Sundown playing, with 20 lines and two levels of genres. */
    private fun show(block: () -> Unit) {
        val lines = (1..20).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val playing =
            track(
                sundown,
                InsightState.Idle(canWrite = false),
                mapOf(SourceId.MUSICBRAINZ to done(evidence = lines)),
                EvidenceSet.of(lines, JvmHasher),
            ).copy(genres = genres)
        val feed =
            HomeFeed(
                view = MutableStateFlow(PipelineView(playing, aiReady = true, writeKey = RunKey.of(normalizer, sundown))),
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

    private fun route() = nav.currentDestination?.route

    private fun onHome() = assertEquals(Home.serializer().descriptor.serialName, route())

    private fun onGenres() = assertEquals(HomePage.Genres.serializer().descriptor.serialName, route())

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
    fun genresAndAChipOpenTheGenresPageAndBackReturnsToHomeWhereItWas() =
        show {
            val unscrolled = top(GENRES_PAGE_LINK_TAG)
            compose.onNodeWithTag(SOURCES_PAGE_LINK_TAG).performScrollTo()
            val scrolled = top(GENRES_PAGE_LINK_TAG)
            assertNotEquals(unscrolled, scrolled)

            compose.onNodeWithTag(GENRES_PAGE_LINK_TAG).performClick()
            compose.waitForIdle()
            onGenres()
            compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").assertExists()
            backArrow()
            onHome()
            assertEquals(scrolled, top(GENRES_PAGE_LINK_TAG))

            compose.onNodeWithTag(folk).performClick()
            compose.waitForIdle()
            onGenres()
            backArrow()
            onHome()
            assertEquals(scrolled, top(GENRES_PAGE_LINK_TAG))
        }

    @Test
    fun afterABackSwipeStartedAndCancelledThePagesBackArrowStillReturnsHome() =
        show {
            open(GENRES_PAGE_LINK_TAG)
            onGenres()
            cancelledBackSwipe()
            // Still on the page, and the page is on top of the stack, though not resumed.
            onGenres()
            backArrow()
            onHome()
            // And Home's links work after it.
            open(folk)
            onGenres()
        }

    @Test
    fun aTapOnHomeWhileItComesBackFromAPageOpensThePage() =
        show {
            open(GENRES_PAGE_LINK_TAG)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag(PAGE_BACK_TAG).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            onHome()
            // Home is on top again but still coming in, short of resumed, when the tap lands.
            assertNotEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry?.lifecycle?.currentState)
            compose.onNodeWithTag(GENRES_PAGE_LINK_TAG).performSemanticsAction(SemanticsActions.OnClick)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            onGenres()
        }

    @Test
    fun aDoubleTapOnAChipOpensOnePageAndADoubleTapOnBackReturnsHomeOnce() =
        show {
            // Both taps land before the page comes in, as a quick double tap does.
            compose.onNodeWithTag(folk).performScrollTo().performTouchInput {
                click()
                click()
            }
            compose.waitForIdle()
            onGenres()
            assertEquals(Home.serializer().descriptor.serialName, nav.previousBackStackEntry?.destination?.route)
            compose.onNodeWithTag(PAGE_BACK_TAG).performTouchInput {
                click()
                click()
            }
            compose.waitForIdle()
            onHome()
            compose.onNodeWithTag(GENRES_PAGE_LINK_TAG).assertExists()
        }
}

// Where the test's back swipe starts, down the screen's edge, and how far in it is drawn, in pixels.
private const val EDGE_Y = 900f
private const val SWIPE_X = 300f
