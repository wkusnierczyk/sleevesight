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
 * *Read aloud* of the song's, the artist's and the album's insights (#894): once written, in an
 * item's panel on Home as on the item's page, at the end of the credit's row under the text (#959,
 * #1162), none in the panel's heading, whose *Close* stays on the kind's line; a screen reader reads
 * the heading, *Close*, the text, the credit, then *Read aloud*.
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

    // In the panel on Home as on the page (#959, #1162): one *Read aloud*, on the credit's row under
    // the text, after the credit; none in the heading, whose *Close* stays on the kind's line.
    private fun readAloudOnTheCreditsRowNotInTheHeading() =
        home {
            assertEquals(1, compose.onAllNodes(hasTestTag(READ)).fetchSemanticsNodes().size)
            val inHeading = hasTestTag(READ) and hasAnyAncestor(hasTestTag("$PANEL:heading"))
            assertEquals("not in the heading", 0, compose.onAllNodes(inHeading, true).fetchSemanticsNodes().size)
            val speaker = tagged(READ)
            val credit = compose.onNodeWithText(CREDIT).rect()
            val text = compose.onNodeWithText("Gordon Lightfoot was", substring = true).rect()
            assertTrue("$speaker $credit", speaker.top <= credit.center.y && credit.center.y <= speaker.bottom)
            assertTrue("after the credit", speaker.left >= credit.right - 1)
            assertTrue("under the text", speaker.top >= text.bottom - 1)
            assertTrue("Close on the kind's line", near(tagged("$PANEL:close").center.y, kind().center.y))
        }

    @Test
    fun inAnItemsPanelReadAloudIsOnTheCreditsRowNotInTheHeading() = readAloudOnTheCreditsRowNotInTheHeading()

    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi", fontScale = 2f)
    fun atTwiceTheFontReadAloudStaysOnTheCreditsRow() = readAloudOnTheCreditsRowNotInTheHeading()

    // As they show: the heading, *Close*, the text, the credit, then *Read aloud*.
    @Test
    fun aScreenReaderReadsTheHeadingCloseTheTextTheCreditThenReadAloud() =
        home {
            val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(PANEL))).fetchSemanticsNodes()
            val tags = nodes.map { it.config.getOrNull(SemanticsProperties.TestTag) }

            fun says(words: String) =
                nodes.indexOfFirst { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(words) } == true
                }
            val order =
                listOf(
                    tags.indexOf("$PANEL:heading"),
                    tags.indexOf("$PANEL:close"),
                    says("Gordon Lightfoot was"),
                    says(CREDIT),
                    tags.indexOf(READ),
                )
            assertTrue(order.toString(), order.all { it >= 0 })
            assertEquals(tags.toString(), order.sorted(), order)
        }

    @Test
    fun onTheItemsPageReadAloudIsOnTheCreditsRow() =
        show({ NowPlayingPageContent(HomePage.Item(InsightItem.ARTIST.name), state, actions) {} }) {
            assertEquals(1, compose.onAllNodes(hasTestTag(READ)).fetchSemanticsNodes().size)
            val speaker = tagged(READ)
            val credit = compose.onNodeWithText(CREDIT).rect()
            assertTrue("$speaker $credit", speaker.top <= credit.center.y && credit.center.y <= speaker.bottom)
        }

    private companion object {
        val PANEL = itemPanelTag(InsightItem.ARTIST)
        val READ = itemReadAloudTag(InsightItem.ARTIST)
        const val CREDIT = "Written by Claude Haiku 4.5."

        // How near two middles must be, in dp, to count as on one line.
        const val WITHIN = 3f
    }
}
