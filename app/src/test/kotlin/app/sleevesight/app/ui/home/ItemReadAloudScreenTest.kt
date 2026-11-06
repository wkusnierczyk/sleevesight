package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.speech.FakeFocus
import app.sleevesight.app.speech.FakeSpeech
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * *Read aloud* of the song's, the artist's and the album's insights (#894): in an item's panel on
 * Home, once written, in the heading beside *Close*, both centred on the kind's line, read after the
 * heading and before *Close*, and not in the body; on the item's page, where it always was, on the
 * byline's row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class ItemReadAloudScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val readAloud =
        ReadAloud(
            FakeSpeech(),
            FakeFocus(),
            MutableStateFlow(Settings()),
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Main.immediate),
        ) { Locale.UK }

    // The artist's insight written, the others offered.
    private val notes =
        object : ItemNotes {
            override fun item(
                track: TrackState,
                item: InsightItem,
            ): Flow<ItemShown> =
                flowOf(
                    if (item == InsightItem.ARTIST) {
                        ItemShown.Shown(
                            written("Gordon Lightfoot was a Canadian singer [W1]."),
                            ItemEvidence(evidence, mapOf("W1" to "W1")),
                            "Claude Haiku 4.5",
                        )
                    } else {
                        ItemShown.Offered
                    },
                )

            override fun write(
                track: TrackState,
                item: InsightItem,
                ask: ItemAsk,
            ) = Unit
        }

    private val actions = NowPlayingActions(normalizer::normalize, itemNotes = notes, readAloud = readAloud)

    private val state =
        track().let {
            NowPlayingState(
                PipelineView(it, aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        }

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { content() } }
            compose.waitForIdle()
            block()
        }
    }

    private fun home(block: () -> Unit) =
        show({ Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) } }) {
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(PANEL).performScrollTo()
            block()
        }

    private fun SemanticsNodeInteraction.rect(): Rect =
        getUnclippedBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).rect()

    private fun kind() =
        compose
            .onNode(hasText("Artist") and hasAnyAncestor(hasTestTag("$PANEL:heading")), useUnmergedTree = true)
            .rect()

    private fun near(
        a: Float,
        b: Float,
    ) = abs(a - b) <= WITHIN

    private fun readAloudInTheHeadingOnTheKindsLine() =
        home {
            assertEquals(1, compose.onAllNodes(hasTestTag(READ)).fetchSemanticsNodes().size)
            val speaker = tagged(READ)
            val close = tagged("$PANEL:close")
            val kind = kind()
            assertTrue("$speaker $kind", near(speaker.center.y, kind.center.y))
            assertTrue("$close $kind", near(close.center.y, kind.center.y))
            // Left of *Close*, and above the name: in the heading, not on the byline's row.
            assertTrue(speaker.right <= close.left + 1)
            assertTrue(speaker.top < tagged("$PANEL:subtitle").bottom)
            assertTrue(speaker.bottom < compose.onNodeWithText("by Claude Haiku 4.5").rect().top)
        }

    @Test
    fun inAnItemsPanelReadAloudIsInTheHeadingBesideCloseOnTheKindsLine() = readAloudInTheHeadingOnTheKindsLine()

    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi", fontScale = 2f)
    fun atTwiceTheFontReadAloudAndCloseStayOnTheKindsLine() = readAloudInTheHeadingOnTheKindsLine()

    @Test
    fun aScreenReaderReadsTheHeadingThenReadAloudThenCloseThenTheBody() =
        home {
            val order =
                compose
                    .onAllNodes(hasAnyAncestor(hasTestTag(PANEL)))
                    .fetchSemanticsNodes()
                    .map { it.config.getOrNull(SemanticsProperties.TestTag) }
            val heading = order.indexOf("$PANEL:heading")
            val read = order.indexOf(READ)
            val close = order.indexOf("$PANEL:close")
            assertTrue(order.toString(), heading in 0 until read && read < close)
            val byline =
                compose
                    .onAllNodes(hasAnyAncestor(hasTestTag(PANEL)))
                    .fetchSemanticsNodes()
                    .indexOfFirst { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "by Claude Haiku 4.5" } ==
                            true
                    }
            assertTrue(order.toString(), close < byline)
        }

    @Test
    fun onTheItemsPageReadAloudStaysOnTheBylinesRow() =
        show({ NowPlayingPageContent(HomePage.Item(InsightItem.ARTIST.name), state, actions) {} }) {
            assertEquals(1, compose.onAllNodes(hasTestTag(READ)).fetchSemanticsNodes().size)
            val speaker = tagged(READ)
            val byline = compose.onNodeWithText("by Claude Haiku 4.5").rect()
            assertTrue("$speaker $byline", speaker.top <= byline.center.y && byline.center.y <= speaker.bottom)
        }

    private companion object {
        val PANEL = itemPanelTag(InsightItem.ARTIST)
        val READ = itemReadAloudTag(InsightItem.ARTIST)

        // How near two middles must be, in dp, to count as on one line.
        const val WITHIN = 3f
    }
}
