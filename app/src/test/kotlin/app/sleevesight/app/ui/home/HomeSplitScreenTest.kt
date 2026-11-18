package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.HOME_HEADER_TAG
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Home split in two from 600 dp wide (#1097): the cover and the position on the left, as tall as Home
 * and at most half its width; the header over the right side only, the player row first under it,
 * then the title, all on the right, which scrolls on its own while the cover stays; a screen reader
 * reads it in portrait's order. Nothing playing, or an ad, is one column at most 600 dp wide, centred.
 * Narrower, Home is as in portrait; turning the phone keeps the open panel, the turned cover and
 * the scroll position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w891dp-h411dp-land-420dpi")
class HomeSplitScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    // The title differs from the album's name, so each is found alone.
    private val playing: NowPlaying = carefree

    private fun state(
        playing: NowPlaying? = this.playing,
        ad: AdPlaying? = null,
    ) = NowPlayingState(
        // MusicBrainz answered: the artists named at once, so nothing moves under a tap (#1355).
        PipelineView(playing?.let { track(it, sources = mapOf(SourceId.MUSICBRAINZ to done())) }, aiReady = true),
        playing,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        // A cover, not the placeholder: the art alone, as a screen reader meets it.
        art = playing?.let { Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888) },
        settings = Settings(),
        ad = ad,
    )

    private val actions = NowPlayingActions(normalizer::normalize)

    private var current by mutableStateOf(state())

    // Home as wide as [width] says, or as the screen when null: narrower, as a phone held upright.
    private var width by mutableStateOf<Dp?>(null)

    // Or a window of its own, [short], and its font at [fontScale] times.
    private var short by mutableStateOf<DpSize?>(null)
    private var fontScale by mutableStateOf<Float?>(null)

    // The screen's saved state, as an activity keeps it ([turn]).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun show(
        initial: NowPlayingState = state(),
        block: () -> Unit,
    ) {
        current = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SleevesightTheme {
                                val density = LocalDensity.current
                                val font = fontScale?.let { Density(density.density, it) } ?: density
                                val window =
                                    short?.let { Modifier.requiredSize(it) }
                                        ?: width?.let { w -> Modifier.requiredWidth(w) }
                                        ?: Modifier
                                Box(window.testTag(WINDOW_TAG)) {
                                    CompositionLocalProvider(LocalDensity provides font) {
                                        HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) {
                                            NowPlayingContent(current, actions)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    // The phone turned: the screen saved, dropped, and composed afresh [wide] or not from what was
    // saved, as Android does for an activity recreated on a rotation.
    private fun turn(wide: Boolean) {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        width = if (wide) null else PORTRAIT
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private val accessibility get() = shadowOf(app.getSystemService(AccessibilityManager::class.java))

    @After
    fun close() {
        accessibility.setTouchExplorationEnabled(false)
        accessibility.setEnabledAccessibilityServiceList(emptyList())
        accessibility.setEnabled(false)
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun dp(value: Float) =
        with(
            compose
                .onRoot()
                .fetchSemanticsNode()
                .layoutInfo.density,
        ) { value.toDp() }

    private val title get() = compose.onNodeWithText(playing.title).fetchSemanticsNode().boundsInRoot

    // Where [tag]'s node is laid out, whether scrolled into view or not.
    private fun laidOut(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private val titleLaidOut get() =
        compose.onNodeWithText(playing.title).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }

    private val position get() =
        compose.onNodeWithContentDescription(text(R.string.home_progress)).fetchSemanticsNode().boundsInRoot

    @Test
    fun wideTheCoverIsOnTheLeftAsTallAsHomeAndTheContentOnTheRight() =
        show {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val pane = bounds(COVER_PANE_TAG)
            val cover = bounds(COVER_TAG)
            // The cover on the left, square, at most half the width, the position under it.
            assertTrue("cover in the pane: $cover in $pane", cover.left >= pane.left && cover.right <= pane.right)
            assertEquals(cover.width, cover.height, 1f)
            assertTrue("at most half: $pane of $root", pane.width <= root.width / 2 + 1f)
            assertTrue("the position under the cover", position.top >= cover.bottom && position.bottom <= root.bottom)
            assertEquals(cover.width, position.width, 1f)
            // As large as the height lets it be: the cover and the position fill the pane's height,
            // less its margins.
            assertEquals(12f, dp(root.bottom - position.bottom).value, 2f)
            // The header over the right side only, the title under it (the player row went into the
            // header's player menu, #1209), on the screen at once.
            val header = bounds(HOME_HEADER_TAG)
            assertTrue("header right of the cover: $header", header.left >= pane.right && header.top <= root.top + 1f)
            assertTrue("title under the header", title.top >= header.bottom && title.left >= pane.right)
            compose.onNodeWithText(playing.title).assertIsDisplayed()
        }

    // Real text measurement, so the times wrap as they do on the phone.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun inAShortWindowAtALargeFontTheCoverAndTheWrappedPositionFitTheHeight() {
        // 600 × 200 dp at twice the font, an hour-long track: the times take two lines under a cover
        // that narrow, and the cover gives way to them.
        short = DpSize(600.dp, 200.dp)
        fontScale = 2f
        val long = playing.copy(duration = 1.hours + 5.minutes)
        val paused = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PAUSED, position = 1.hours + 2.minutes)
        show(state(long).let { NowPlayingState(it.view, long, paused, it.art, it.settings) }) {
            val window = laidOut(WINDOW_TAG)
            val cover = laidOut(COVER_TAG)
            val position =
                compose.onNodeWithContentDescription(text(R.string.home_progress)).fetchSemanticsNode().let {
                    Rect(it.positionInRoot, it.size.toSize())
                }
            assertTrue("the position under the cover: $position, $cover", position.top >= cover.bottom)
            assertTrue("within the window: $position in $window", position.bottom <= window.bottom + 1f)
            assertTrue("the times as wide as the cover", position.width <= cover.width + 1f)
        }
    }

    @Test
    fun theRightSideScrollsOnItsOwnWhileTheCoverStays() =
        show {
            val cover = bounds(COVER_TAG)
            val header = bounds(HOME_HEADER_TAG)
            val before = title
            compose.onNodeWithTag(SOURCES_LINE_TAG).performScrollTo()
            compose.waitForIdle()
            assertTrue("the right side scrolled", title.top < before.top)
            assertEquals(cover, bounds(COVER_TAG))
            assertEquals(header, bounds(HOME_HEADER_TAG))
        }

    @Test
    fun theCoverStillTurnsOverToItsTracklist() =
        show {
            compose.onNodeWithTag(COVER_TAG).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(R.string.home_tracklist)).assertExists()
            assertEquals(1, count(COVER_PANE_TAG))
        }

    @Test
    fun aScreenReaderReadsTheHeaderThePositionThenTheTitle() {
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        accessibility.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        show {
            val nodes =
                listOf(
                    compose.onNodeWithContentDescription(text(R.string.home_scroll_to_top, text(R.string.app_name))),
                    // The cover's side: the position's bar, then its times. The cover itself is not in
                    // the order Compose reports, here or in portrait (a node under it, of the art's
                    // clip, makes it no leaf): a screen reader meets it where it is, before the position.
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)),
                    compose.onNodeWithText(playing.title),
                ).map { it.fetchSemanticsNode() }
            val order = readingOrder(nodes.first())
            val places = nodes.map { order.indexOf(it.id) }
            assertTrue("each read: $places in $order", places.all { it >= 0 })
            assertEquals(places.sorted(), places)
        }
    }

    @Test
    fun nothingPlayingIsOneColumnAtMostSixHundredWideAndCentred() =
        show(state(playing = null)) {
            compose.awaitIdleHome()
            assertEquals(0, count(COVER_PANE_TAG))
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val column = bounds(NOTHING_PLAYING_TAG)
            assertTrue("at most 600 dp: ${dp(column.width)}", dp(column.width) <= ONE_COLUMN_MAX_WIDTH)
            assertEquals(root.center.x, column.center.x, 2f)
            // The header across the whole width.
            assertEquals(root.width, bounds(HOME_HEADER_TAG).width, 1f)
        }

    @Test
    fun anAdIsOneColumnAsNothingPlayingIs() =
        show(state(playing = null, ad = AdPlaying(Settings.SPOTIFY))) {
            assertEquals(0, count(COVER_PANE_TAG))
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val column = bounds(AD_HOME_TAG)
            assertTrue("at most 600 dp: ${dp(column.width)}", dp(column.width) <= ONE_COLUMN_MAX_WIDTH)
            assertEquals(root.center.x, column.center.x, 2f)
        }

    @Test
    fun narrowerThanSixHundredHomeIsAsInPortrait() {
        width = 599.dp
        show {
            assertEquals(0, count(COVER_PANE_TAG))
            val header = laidOut(HOME_HEADER_TAG)
            val cover = laidOut(COVER_TAG)
            assertTrue("cover under the header: $cover", cover.top >= header.bottom)
            assertTrue("title under the cover", titleLaidOut.top >= cover.bottom)
        }
    }

    @Test
    fun turningThePhoneKeepsTheOpenPanelTheTurnedCoverAndTheScroll() =
        show {
            tapUntil(DATES_CHIP_TAG) { count(DATES_PANEL_TAG) == 1 }
            tapUntil(COVER_TAG) { turned() }
            compose.onNodeWithTag(SOURCES_LINE_TAG).performScrollTo()
            val header = compose.onNodeWithTag(HOME_HEADER_TAG)
            // Upright: the same panel open, the tracklist on the cover's back, Home scrolled.
            turn(wide = false)
            assertEquals(0, count(COVER_PANE_TAG))
            assertEquals(1, count(DATES_PANEL_TAG))
            compose.onNodeWithContentDescription(text(R.string.home_tracklist)).assertExists()
            assertTrue(
                "scrolled: the cover's top out of view",
                coverScrolled(header.fetchSemanticsNode().boundsInRoot),
            )
            // And back sideways.
            turn(wide = true)
            assertEquals(1, count(COVER_PANE_TAG))
            assertEquals(1, count(DATES_PANEL_TAG))
            compose.onNodeWithContentDescription(text(R.string.home_tracklist)).assertExists()
        }

    // Taps [tag] until [done]: the art's colours arrive off the main thread, at no set time, and what
    // they recreate (all under them) loses a tap made meanwhile.
    private fun tapUntil(
        tag: String,
        done: () -> Boolean,
    ) {
        repeat(TAPS) {
            if (done()) return
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            runCatching { compose.waitUntil(WAIT_MS) { done() } }
        }
        assertTrue("$tag tapped", done())
    }

    private fun turned() =
        compose.onAllNodesWithContentDescription(text(R.string.home_tracklist)).fetchSemanticsNodes().isNotEmpty()

    // Whether the cover's top has scrolled up under [header], as it has only on a Home scrolled.
    private fun coverScrolled(header: Rect): Boolean = laidOut(COVER_TAG).top < header.bottom

    // The nodes as a screen reader goes through them, from [first]: each node's next, as Compose
    // reports it to an accessibility service.
    private fun readingOrder(first: SemanticsNode): List<Int> {
        val order = mutableListOf<Int>()
        compose.runOnUiThread {
            val view = composeView(first.root as View)
            val provider = checkNotNull(view.accessibilityNodeProvider) { "no node provider" }
            var id: Int? = first.id
            while (id != null && id !in order && order.size < MAX_NODES) {
                order += id
                val info = checkNotNull(provider.createAccessibilityNodeInfo(id)) { "no node $id" }
                provider.addExtraDataToAccessibilityNodeInfo(id, info, TRAVERSAL_BEFORE, null)
                id = info.extras.getInt(TRAVERSAL_BEFORE, -1).takeIf { it != -1 }
            }
        }
        return order
    }

    private fun composeView(root: View): View =
        if (root.javaClass.simpleName == "AndroidComposeView") {
            root
        } else {
            (root as ViewGroup).children.firstNotNullOf { child -> runCatching { composeView(child) }.getOrNull() }
        }

    private companion object {
        // A phone held upright.
        val PORTRAIT = 411.dp

        // The extra Compose fills with the node a screen reader goes to next.
        const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

        const val MAX_NODES = 200

        const val ART_PX = 8

        const val TAPS = 3

        const val WINDOW_TAG = "window"
    }
}
