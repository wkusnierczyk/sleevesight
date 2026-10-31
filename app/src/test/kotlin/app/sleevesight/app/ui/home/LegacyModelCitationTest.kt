package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.withWeb
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An insight kept from before the model's own knowledge was cited `[AI]` (#668) cites it `[M]`, in
 * the synthesis cache under the same prompt version: it shows and reads as one written now. So does
 * one kept from before web search's lines were `S1`, `S2` (#769), citing them `[G1]`, `[G2]`.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyModelCitationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(
        state: NowPlayingState,
        block: () -> Unit,
    ) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { "Spotify" })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    @Test
    fun aKeptInsightCitingMShowsItsCitationAsTheAiChipAndReadsAsTheModelsOwnKnowledge() {
        val reply = "It topped the charts [W1]. It is loved [M]."
        val kept = track(insight = written(reply, cached = true, allowModel = true))
        val state =
            NowPlayingState(
                PipelineView(kept, aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        show(state) {
            val node =
                compose
                    .onNode(
                        hasText("It topped the charts", substring = true) and
                            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
                    ).performScrollTo()
            val prose =
                node
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .first { "It is loved" in it.text }
            val spans = prose.spanStyles.map { prose.text.substring(it.start, it.end).trim() }
            assertEquals("no M chip: $spans", 0, spans.count { it == "M" })
            val chip = prose.spanStyles.single { prose.text.substring(it.start, it.end).trim() == "AI" }
            assertEquals(lightColorScheme().tertiaryContainer, chip.item.background)
            node.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        "It topped the charts (${app.getString(R.string.home_cite, "W1")}). " +
                            "It is loved (${app.getString(R.string.home_cite_model)}).",
                    ),
                ),
            )
        }
    }

    @Test
    fun aKeptInsightCitingG1ShowsItsCitationAsTheS1ChipAndReachesWebSearchsLine() {
        val reply = "It topped the charts [W1]. It went gold [G1]."
        val kept = track(insight = written(reply, cached = true, from = withWeb), evidence = withWeb)
        val state =
            NowPlayingState(
                PipelineView(kept, aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        show(state) {
            val node =
                compose
                    .onNode(
                        hasText("It topped the charts", substring = true) and
                            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
                    ).performScrollTo()
            val prose =
                node
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .first { "It went gold" in it.text }
            val spans = prose.spanStyles.map { prose.text.substring(it.start, it.end).trim() }
            assertEquals("no G1 chip: $spans", 0, spans.count { it == "G1" })
            assertEquals("one S1 chip: $spans", 1, spans.count { it == "S1" })
            // Read as a line's citation, not as one of no line.
            node.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        "It topped the charts (${app.getString(R.string.home_cite, "W1")}). " +
                            "It went gold (${app.getString(R.string.home_cite, "S1")}).",
                    ),
                ),
            )
        }
    }
}
