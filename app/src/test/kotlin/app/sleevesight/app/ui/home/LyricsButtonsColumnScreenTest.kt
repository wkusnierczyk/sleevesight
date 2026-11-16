package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import app.sleevesight.app.ui.theme.CHIP_PADDING
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * The *Lyrics* panel's *Find the lyrics* and *Explain the song* as one column (#1343), as the chips
 * under *Key insights* are (#1286), on Home as drawn on a phone: one width, the wider label filling
 * its button with its **AI** mark after it; the mark at the same place at each button's end (its
 * left end right to left), the other label centred in the room left of it. At the default font size
 * and twice it, under both layout directions, offered and not ready. Each still reads
 * "<label>, uses AI". With `SLEEVESIGHT_PICTURES` set, [pictures] saves the panel there, light and
 * dark, as [KeyInsightsColumnScreenTest] saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsButtonsColumnScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val feed =
        object : AiSearchFeed {
            val states = SearchKind.entries.associateWith { MutableStateFlow<AiSearch>(AiSearch.Offered) }

            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = states.getValue(kind)

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

    // MusicBrainz found the song but no lyrics page: both buttons.
    private val state =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to
                        SourceState.Done(
                            SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = WorkLyrics()),
                            cached = false,
                        ),
                    SourceId.LASTFM to done(),
                ),
        ).let { track ->
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        }

    private val actions = NowPlayingActions(normalizer::normalize, aiSearches = feed)

    private fun show(
        direction: LayoutDirection = LayoutDirection.Ltr,
        dark: Boolean = false,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        SleevesightTheme(darkTheme = dark) {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    NowPlayingContent(state, actions)
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LYRICS_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(explainTag(AI_SEARCH_TAG)).performScrollTo()
            compose.waitForIdle()
            block()
        }
    }

    // [dp] in whole pixels, as the layout rounds it.
    private fun px(dp: Dp): Float = with(compose.density) { dp.roundToPx().toFloat() }

    private fun near(
        what: String,
        expected: Float,
        actual: Float,
    ) = assertTrue("$what: $expected expected, $actual", abs(expected - actual) <= TOLERANCE)

    // A button of the column: its bounds, its label's and its mark's.
    private class Button(
        val name: String,
        val button: Rect,
        val label: Rect,
        val mark: Rect,
    )

    private fun button(
        tag: String,
        label: Int,
    ): Button {
        val inside = hasAnyAncestor(hasTestTag(tag))
        val name = app.getString(label)

        fun bounds(
            matcher: SemanticsMatcher,
            unmerged: Boolean = true,
        ) = compose.onNode(matcher, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot
        return Button(
            name,
            bounds(hasTestTag(tag), unmerged = false),
            bounds(hasText(name) and inside),
            bounds(hasTestTag(AI_MARK_TAG) and inside),
        )
    }

    // Every check of the column, on the panel shown, laid out in [direction].
    private fun checkColumn(direction: LayoutDirection) {
        val buttons =
            listOf(
                button(AI_SEARCH_TAG, R.string.home_lyrics_find),
                button(explainTag(AI_SEARCH_TAG), R.string.home_lyrics_explain),
            )
        val padding = px(CHIP_PADDING)
        val gap = px(MARK_GAP)
        val ltr = direction == LayoutDirection.Ltr
        val width = buttons.first().button.width
        // One width; the widest fits its label, the gap and the mark exactly, with the padding.
        for (b in buttons) near("${b.name}'s width", width, b.button.width)
        near("the column's width", buttons.maxOf { it.label.width + gap + it.mark.width } + 2 * padding, width)
        // The mark at the button's end, the same place on both; the label centred in the room before it.
        val first = buttons.first()
        val firstMark = first.mark.left - first.button.left
        for (b in buttons) {
            val end = if (ltr) b.button.right - padding - b.mark.right else b.mark.left - (b.button.left + padding)
            near("${b.name}'s mark at its end", 0f, end)
            near("${b.name}'s mark's place", firstMark, b.mark.left - b.button.left)
            val room =
                if (ltr) {
                    (b.button.left + padding + b.mark.left - gap) / 2
                } else {
                    (b.mark.right + gap + b.button.right - padding) / 2
                }
            near("${b.name}'s label's centre", room, b.label.center.x)
        }
    }

    private fun check(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        for (direction in LayoutDirection.entries) {
            for (search in listOf(AiSearch.Offered, AiSearch.Unready)) {
                feed.states.values.forEach { it.value = search }
                show(direction) { checkColumn(direction) }
            }
        }
    }

    @Test
    fun theButtonsAreOneColumnAtTheDefaultFontSize() = check(1f)

    @Test
    fun theButtonsAreOneColumnAtTwiceIt() = check(2f)

    @Test
    fun theButtonsReadAsBefore() {
        val buttons =
            listOf(
                AI_SEARCH_TAG to R.string.home_lyrics_find,
                explainTag(AI_SEARCH_TAG) to R.string.home_lyrics_explain,
            )
        for (search in listOf(AiSearch.Offered, AiSearch.Unready)) {
            feed.states.values.forEach { it.value = search }
            show {
                for ((tag, label) in buttons) {
                    val name = app.getString(label)
                    val button = compose.onNodeWithTag(tag)
                    button.assertTextEquals(name).assertContentDescriptionEquals("$name, uses AI")
                    if (search == AiSearch.Unready) {
                        button
                            .assertIsNotEnabled()
                            .assert(
                                SemanticsMatcher.expectValue(
                                    SemanticsProperties.StateDescription,
                                    app.getString(R.string.home_ai_search_unready),
                                ),
                            )
                    }
                }
            }
        }
    }

    @Test
    fun pictures() {
        val dir = out ?: return
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (direction in LayoutDirection.entries) {
                for (dark in listOf(false, true)) {
                    show(direction, dark) {
                        val bitmap = compose.onNodeWithTag(LYRICS_PANEL_TAG).captureToImage().asAndroidBitmap()
                        val theme = if (dark) "dark" else "light"
                        val name = "lyrics-${direction.name.lowercase()}-$theme-$size.png"
                        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
    }

    private companion object {
        // Half a pixel: centring a label in its room divides whole pixels by two.
        const val TOLERANCE = 0.5f
    }
}
