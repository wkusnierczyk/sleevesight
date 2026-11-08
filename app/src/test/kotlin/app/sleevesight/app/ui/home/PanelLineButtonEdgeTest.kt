package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * A button at the end of a panel's line ends where the panel's *Close* ends (#1068): in the *Lyrics*
 * panel while the song is read about, *Stop*'s text ends where *Close*'s does, within a pixel, at the
 * default font size and twice it, and *Stop* is still at least 48 dp to touch, to its end. With
 * `SLEEVESIGHT_PICTURES` set, the panel is also saved there as a picture, as
 * `RewrittenNotePicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanelLineButtonEdgeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // *Explain the song* under way, every other search offered.
    private val feed =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> =
                MutableStateFlow(if (kind == SearchKind.EXPLAIN) AiSearch.Searching(web = true) else AiSearch.Offered)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean = true

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) {
                stopped++
            }
        }

    private var stopped = 0

    private val actions = NowPlayingActions(normalizer::normalize, aiSearches = feed)

    private fun state(): NowPlayingState {
        // MusicBrainz found the song, with no lyrics page: *Explain the song* is offered.
        val musicBrainz =
            SourceState.Done(
                SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = WorkLyrics()),
                cached = false,
            )
        val track = track(sources = mapOf(SourceId.MUSICBRAINZ to musicBrainz, SourceId.LASTFM to done()))
        val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
        return NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())
    }

    // The right edge, in the root, of the text [label] inside the control tagged [tag], on screen or
    // scrolled off it.
    private fun textEnd(
        tag: String,
        label: String,
    ): Float =
        compose
            .onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { it.positionInRoot.x + it.size.width }

    private fun check(name: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                                NowPlayingContent(state(), actions)
                                // Room below the panel, so that it scrolls whole into view.
                                Spacer(Modifier.height(120.dp).testTag(END))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LYRICS_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            val stopTag = explainTag(AI_SEARCH_STOP_TAG)
            compose.onNodeWithTag(END).performScrollTo()
            val stop = compose.onNodeWithTag(stopTag)
            compose.waitForIdle()
            picture(name)
            val close = textEnd(LYRICS_PANEL_CLOSE_TAG, app.getString(R.string.page_close))
            val stopEnd = textEnd(stopTag, app.getString(R.string.home_ai_stop))
            assertEquals("$name: Stop's text ends where Close's does", close, stopEnd, 1f)
            val density = app.resources.displayMetrics.density
            val touch = stop.fetchSemanticsNode().touchBoundsInRoot
            assertTrue("$name: Stop is at least 48 dp wide to touch", touch.width >= MIN_TOUCH * density - 1)
            assertTrue("$name: Stop is at least 48 dp high to touch", touch.height >= MIN_TOUCH * density - 1)
            // A tap near its end, where it reaches past the line, still lands on it.
            val before = stopped
            stop.performTouchInput { click(Offset(width - 2 * density, height / 2f)) }
            compose.waitForIdle()
            assertEquals("$name: a tap at Stop's end stops", before + 1, stopped)
        }
    }

    // With `SLEEVESIGHT_PICTURES` set, the screen saved there as [name].
    private fun picture(name: String) {
        val dir = out ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun stopEndsWhereCloseEndsAtEachFontSize() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            check(if (scale == 1f) "lyrics-stop-light-font100" else "lyrics-stop-light-font200")
        }
    }

    private companion object {
        const val MIN_TOUCH = 48
        const val END = "end"
    }
}
