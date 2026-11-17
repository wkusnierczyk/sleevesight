package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.ui.test.assertTextEquals
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
import androidx.core.view.children
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * The key insight's credit (#811, #1162): "Written by {model}" with the effort when not the service's
 * default, naming the model that wrote the insight (its `WrittenBy`), not the one selected now, as *AI
 * services* lists it, or by its id once it is no longer listed; none while it is written, and none
 * with no insight. Under the text, first in its notes (#959), *Read aloud* at the end of its row,
 * which does not say it; nothing of it left in the heading's row; a screen reader reads the heading,
 * the text, the credit, then *Read aloud*.
 * With `SLEEVESIGHT_PICTURES` set, the card is kept there as pictures, light and dark, at the
 * default font size and twice it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreditLineScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

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

    private fun credit() = compose.onNodeWithTag(INSIGHT_BY_TAG, useUnmergedTree = true)

    @Test
    fun aWrittenInsightNamesTheModelThatWroteItNotTheOneSelectedNow() {
        listed()
        home(writtenBy(OPUS)) {
            credit().assertTextEquals("Written by Claude Opus 5.5 with high effort.")
            assertEquals(0, compose.onAllNodesWithText("Claude Sonnet", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aModelNoLongerListedIsNamedByItsId() =
        home(writtenBy(RETIRED)) {
            credit().assertTextEquals("Written by $RETIRED with high effort.")
        }

    @Test
    fun aModelTheAppKnowsIsNamedAsItKnowsIt() =
        home(writtenBy("claude-haiku-5-5")) {
            credit().assertTextEquals("Written by Claude Haiku 5.5 with high effort.")
        }

    // At the default effort, the service's own (none sent) or the one *Settings* chooses until another
    // is set (low), the model alone; one line, never "by {model}" above the text.
    @Test
    fun theDefaultEffortIsNotSaid() {
        for (effort in listOf(null, ReasoningEffort.LOW)) {
            home(writtenBy("claude-haiku-5-5").copy(writtenBy = by("claude-haiku-5-5").copy(effort = effort))) {
                credit().assertTextEquals("Written by Claude Haiku 5.5.")
                val credits = compose.onAllNodesWithText("by Claude Haiku", substring = true, useUnmergedTree = true)
                assertEquals("one line", 1, credits.fetchSemanticsNodes().size)
            }
        }
    }

    // #959: under the text, first in its notes; *Read aloud* at the end of its row; nothing of it in
    // the heading's row.
    @Test
    fun theCreditIsUnderTheTextFirstInItsNotesWithReadAloudAtItsRowsEnd() =
        home(writtenBy("claude-haiku-5-5")) {
            val line = credit().fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithTag(READ_ALOUD_TAG).fetchSemanticsNode().boundsInRoot
            val inHeading = hasTestTag(INSIGHT_BY_TAG) and hasAnyAncestor(hasTestTag(INSIGHT_HEADING_TAG))
            assertEquals("not in the heading's row", 0, compose.onAllNodes(inHeading, true).fetchSemanticsNodes().size)
            val readInHeading = hasTestTag(READ_ALOUD_TAG) and hasAnyAncestor(hasTestTag(INSIGHT_HEADING_TAG))
            assertEquals(
                "Read aloud not in the heading's row",
                0,
                compose.onAllNodes(readInHeading).fetchSemanticsNodes().size,
            )
            val text = compose.onNodeWithText("Sundown was recorded", substring = true).fetchSemanticsNode()
            assertTrue("under the text", line.top >= text.boundsInRoot.bottom)
            assertTrue("before Read aloud", line.right <= button.left)
            assertTrue("in Read aloud's row", line.top < button.bottom && line.bottom > button.top)
            val note = compose.onNodeWithText(NOTE).fetchSemanticsNode().boundsInRoot
            assertTrue("above the grounding note", line.bottom <= note.top)
        }

    @Test
    fun noCreditWhileTheInsightIsWritten() {
        home(InsightState.Writing("Sundown was", model = "claude-haiku-5-5")) { credit().assertDoesNotExist() }
        home(InsightState.Writing("", model = "claude-haiku-5-5")) { credit().assertDoesNotExist() }
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
        for (state in none) home(state) { credit().assertDoesNotExist() }
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
    private fun card(
        dark: Boolean,
        insight: InsightState,
        block: (MainActivity) -> Unit,
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
            scenario.onActivity(block)
        }
    }

    private fun picture(
        name: String,
        dark: Boolean,
        insight: InsightState,
    ) = card(dark, insight) {
        credit().assertExists()
        val dir = out ?: return@card
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // A screen reader reads the heading, the text, the credit, then *Read aloud*, in the order Compose
    // gives it (each node names the one read after it), as they show, at the default font size and
    // twice it: for an insight just written and one kept from before.
    @Test
    fun aScreenReaderReadsTheHeadingTheTextTheCreditThenReadAloud() {
        val accessibility =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        // A screen reader running: Compose builds nodes only for an enabled service.
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        val kept = writtenBy("claude-haiku-5-5").copy(cached = true)
        val parts = listOf(HEADING, TEXT, BYLINE, READ_ALOUD)
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for ((what, insight) in listOf("just written" to writtenBy("claude-haiku-5-5"), "kept" to kept)) {
                val read = readFromHeading(insight)
                assertEquals("$what at $scale: in $read", parts, read.filter { it in parts })
            }
        }
    }

    // What a screen reader reads from the heading of the card showing [insight] on, past *Read aloud*,
    // each node named for what it is ([HEADING], [BYLINE], [READ_ALOUD], [TEXT]) or else [OTHER]:
    // *Read aloud*'s tooltip anchor comes between the credit and the button.
    private fun readFromHeading(insight: InsightState): List<String> {
        var read = emptyList<String>()
        card(dark = false, insight) { activity ->
            val provider = checkNotNull(composeView(activity.window.decorView).accessibilityNodeProvider)
            val named =
                listOfNotNull(
                    HEADING to compose.onNodeWithText("Key insights", useUnmergedTree = true),
                    BYLINE to credit(),
                    (READ_ALOUD to compose.onNodeWithTag(READ_ALOUD_TAG)).takeIf { insight is InsightState.Written },
                    TEXT to compose.onNodeWithText("Sundown was recorded", substring = true, useUnmergedTree = true),
                ).associate { (name, node) -> node.fetchSemanticsNode().id to name }
            val first = named.entries.single { it.value == HEADING }.key
            read =
                generateSequence(first) { id ->
                    checkNotNull(provider.createAccessibilityNodeInfo(id)).extras.getInt(AFTER).takeIf { it != 0 }
                }.take(MAX_STEPS)
                    .map { named[it] ?: OTHER }
                    .toList()
        }
        return read
    }

    // The view Compose draws into, under [root].
    private fun composeView(root: View): View =
        if (root.javaClass.simpleName == "AndroidComposeView") {
            root
        } else {
            (root as ViewGroup).children.firstNotNullOf { child -> runCatching { composeView(child) }.getOrNull() }
        }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                picture("credit-written-$theme-$size", dark, writtenBy("claude-haiku-5-5"))
            }
        }
    }

    private companion object {
        const val SONNET = "claude-sonnet-5-5"
        const val OPUS = "claude-opus-5-5"
        const val RETIRED = "claude-retired-0"

        // Where Compose puts, for tests, the node a screen reader reads after a node.
        const val AFTER = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

        // How far the traversal is followed from the heading: past *Read aloud*, a few nodes away.
        const val MAX_STEPS = 16

        const val NOTE = "Dimmed text is not backed by the sources."

        // What the nodes read from the heading on are.
        const val HEADING = "heading"
        const val BYLINE = "credit"
        const val READ_ALOUD = "Read aloud"
        const val TEXT = "text"
        const val OTHER = "other"
    }
}
