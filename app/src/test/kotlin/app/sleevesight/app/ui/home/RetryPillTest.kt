package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
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
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * *Retry* under a failed *Ask AI* (#1404) is the pill, as every button in Settings is (#1387): its
 * label in the controls' colour (black in light mode, white in dark), not the theme's accent, in
 * light and dark. With `SLEEVESIGHT_PICTURES` set, the screen is also saved there as a picture, as
 * `PanelLineButtonEdgeTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetryPillTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // *Ask AI* for the dates failed; every other search offered.
    private val feed =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> =
                MutableStateFlow(
                    when (kind) {
                        SearchKind.DATES -> AiSearch.Failed("offline")
                        SearchKind.SPELLING -> AiSearch.None
                        else -> AiSearch.Offered
                    },
                )

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
            ) = Unit
        }

    private val actions = NowPlayingActions(normalizer::normalize, aiSearches = feed)

    private fun state(): NowPlayingState {
        val track = track(sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None), SourceId.LASTFM to done()))
        val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
        return NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())
    }

    private fun check(dark: Boolean) {
        var ink = Color.Unspecified
        var accent = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        ink = MaterialTheme.colorScheme.onSurface
                        accent = MaterialTheme.colorScheme.primary
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(state(), actions)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(DATES_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            val retry = compose.onNodeWithTag(RETRY_TAG).performScrollTo()
            compose.waitForIdle()
            val name = if (dark) "ask-ai-retry-dark" else "ask-ai-retry-light"
            out?.let { dir ->
                val screen = compose.onRoot().captureToImage().asAndroidBitmap()
                File(dir, "$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val button = retry.captureToImage().asAndroidBitmap()
            assertTrue("$name: Retry's label is in the controls' colour", pixelsNear(button, ink) >= MIN_INK)
            assertTrue("$name: Retry's label is not in the accent", pixelsNear(button, accent) < MIN_INK)
        }
    }

    @Test
    fun retryUnderAFailedAskAiIsThePillInLight() = check(dark = false)

    @Test
    fun retryUnderAFailedAskAiIsThePillInDark() = check(dark = true)
}
