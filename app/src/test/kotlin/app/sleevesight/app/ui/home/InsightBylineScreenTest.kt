package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.speech.FakeFocus
import app.sleevesight.app.speech.FakeSpeech
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.WrittenBy
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.Locale

/**
 * The byline under the *Key insights* heading (#811): "by {model}", naming the model that wrote the
 * insight (its `WrittenBy`), not the one selected now, as *AI services* lists it, or by its id once it
 * is no longer listed; from the first words streamed, naming the model being called; none with no
 * insight. In the heading's row, under the heading and before *Read aloud*, which does not say it.
 * With `SLEEVESIGHT_PICTURES` set, the card is kept there as pictures, light and dark, at the
 * default font size and twice it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightBylineScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val speech = FakeSpeech()
    private val readAloud =
        ReadAloud(
            speech,
            FakeFocus(),
            MutableStateFlow(Settings()),
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Main.immediate),
        ) { Locale.UK }

    // Claude Sonnet 5.5 selected now, as AI services lists it with Claude Opus 5.5.
    private val selected =
        Settings(aiProvider = ProviderId.ANTHROPIC).copy(aiModels = mapOf(ProviderId.ANTHROPIC to SONNET))

    private fun by(model: String) = WrittenBy(5, ProviderId.ANTHROPIC, model, ReasoningEffort.HIGH)

    private fun writtenBy(model: String): InsightState.Written =
        written("Sundown was recorded in 1973 [MB1]. It was a hit in Canada.").copy(writtenBy = by(model))

    private fun listed() =
        ListedModelNames(app).learn(
            listOf(ModelInfo(SONNET, "Claude Sonnet 5.5", null), ModelInfo(OPUS, "Claude Opus 5.5", null)),
        )

    private fun home(
        shown: InsightState,
        block: () -> Unit,
    ) {
        val actions = NowPlayingActions(normalizer::normalize, readAloud = readAloud)
        val view = PipelineView(track(insight = shown), aiReady = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
                    val state = NowPlayingState(view, sundown, playback, settings = selected)
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.onNodeWithTag(WRITE_HALVES_TAG).performScrollTo()
            block()
        }
    }

    private fun byline() = compose.onNodeWithTag(INSIGHT_BY_TAG, useUnmergedTree = true)

    @Test
    fun aWrittenInsightNamesTheModelThatWroteItNotTheOneSelectedNow() {
        listed()
        home(writtenBy(OPUS)) {
            byline().assertExists()
            compose.onNodeWithText("by Claude Opus 5.5").assertExists()
            assertEquals(0, compose.onAllNodesWithText("Claude Sonnet", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aModelNoLongerListedIsNamedByItsId() =
        home(writtenBy(RETIRED)) {
            compose.onNodeWithText("by $RETIRED").assertExists()
        }

    @Test
    fun aModelTheAppKnowsIsNamedAsItKnowsIt() =
        home(writtenBy("claude-haiku-5-5")) {
            compose.onNodeWithText("by Claude Haiku 5.5").assertExists()
        }

    @Test
    fun theBylineIsUnderTheHeadingInItsRowBeforeReadAloud() =
        home(writtenBy("claude-haiku-5-5")) {
            val line = byline().fetchSemanticsNode().boundsInRoot
            val heading = compose.onNodeWithText("Key insights").fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithTag(READ_ALOUD_TAG).fetchSemanticsNode().boundsInRoot
            val inRow = hasTestTag(INSIGHT_BY_TAG) and hasAnyAncestor(hasTestTag(INSIGHT_HEADING_TAG))
            assertEquals("in the heading's row", 1, compose.onAllNodes(inRow, true).fetchSemanticsNodes().size)
            assertTrue("under the heading", line.top >= heading.bottom)
            assertTrue("before Read aloud", line.right <= button.left)
            val text = compose.onNodeWithText("Sundown was recorded", substring = true).fetchSemanticsNode()
            assertTrue("before the text", line.bottom <= text.boundsInRoot.top)
        }

    @Test
    fun whileTheInsightIsWrittenItNamesTheModelBeingCalledFromTheFirstWords() {
        home(InsightState.Writing("Sundown was", model = "claude-haiku-5-5")) {
            compose.onNodeWithText("by Claude Haiku 5.5").assertExists()
        }
        home(InsightState.Writing("", model = "claude-haiku-5-5")) { byline().assertDoesNotExist() }
    }

    @Test
    fun noInsightNoByline() {
        val none =
            listOf(
                InsightState.Idle(canWrite = true),
                InsightState.Idle(canWrite = false),
                InsightState.NoEvidence,
                InsightState.Failed("timeout"),
                InsightState.Stopped,
                // An insight no pipeline wrote names no model.
                written("Sundown was recorded in 1973 [MB1]."),
            )
        for (state in none) home(state) { byline().assertDoesNotExist() }
    }

    @Test
    fun readAloudDoesNotSayIt() =
        home(writtenBy("claude-haiku-5-5")) {
            compose.onNodeWithTag(READ_ALOUD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(
                listOf(listOf("Sundown was recorded in 1973.", "Unverified: It was a hit in Canada.")),
                speech.spoken,
            )
        }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // The card alone, 16 dp in, on the theme's background and in its text colour, as on Home.
    private fun picture(
        name: String,
        dark: Boolean,
        insight: InsightState,
    ) {
        val context =
            InsightContext(track(insight = insight), aiReady = true, insightsOn = true, false, InsightOffers())
        val control = ReadAloudControl(reading = false, onRead = {}, onStop = {})
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp)) {
                                InsightCard(
                                    insight,
                                    context,
                                    InsightActions({}, {}, {}, onReport = { _, _ -> }),
                                    readAloud = control,
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            byline().assertExists()
            val dir = out ?: return
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                picture("insight-by-written-$theme-$size", dark, writtenBy("claude-haiku-5-5"))
                picture(
                    "insight-by-writing-$theme-$size",
                    dark,
                    InsightState.Writing("Sundown was recorded in 1973 and", model = "claude-haiku-5-5"),
                )
            }
        }
    }

    private companion object {
        const val SONNET = "claude-sonnet-5-5"
        const val OPUS = "claude-opus-5-5"
        const val RETIRED = "claude-retired-0"
    }
}
