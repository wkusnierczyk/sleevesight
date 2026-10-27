package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A switched-off player's row on the idle Home (#675): as tall as a followed player's, 56 dp, with
 * *Enable in Settings* at the row's end, its 48 dp touch target inside the row; at a large font,
 * the link under the name, its text right under the name's.
 */
@RunWith(RobolectricTestRunner::class)
// Real text metrics, so whether the name and the link fit on one line is as on a phone.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class IdlePlayerRowLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val names = mapOf(SPOTIFY to "Spotify", AUDIOTEKA to "Audioteka")
    private val players = Settings(followedPlayers = mapOf(SPOTIFY to true, AUDIOTEKA to false))

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { names[it] ?: it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(NowPlayingState(PipelineView(), playing = null, settings = players), actions)
                    }
                }
            }
            compose.awaitIdleHome()
            block()
        }
    }

    private fun px(dp: Float) = dp * compose.density.density

    private fun row(player: String): Rect =
        compose
            .onNodeWithTag(idlePlayerTag(player))
            .fetchSemanticsNode()
            .boundsInRoot

    private fun link() = compose.onNodeWithText(app.getString(R.string.home_player_enable)).fetchSemanticsNode()

    // The switched-off player's name, read as *Play on Audioteka, not followed*.
    private fun name(): Rect =
        compose
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(app.getString(R.string.home_play_on_not_followed, "Audioteka")),
                ),
                useUnmergedTree = true,
            ).fetchSemanticsNode()
            .boundsInRoot

    @Test
    fun aSwitchedOffPlayersRowIsAsTallAsAFollowedOnes() =
        show {
            val followed = row(SPOTIFY)
            val off = row(AUDIOTEKA)
            assertEquals(px(ROW_DP), followed.height, 1f)
            assertEquals(followed.height, off.height, 1f)
        }

    @Test
    fun theLinkIsAtTheRowsEndLevelWithTheNameItsTargetInsideTheRow() =
        show {
            val off = row(AUDIOTEKA)
            val link = link().boundsInRoot
            val name = name()
            assertTrue("target ${link.height}", link.height >= px(TARGET_DP) - 1f)
            assertTrue("$link in $off", link.top >= off.top - 1f && link.bottom <= off.bottom + 1f)
            // At the row's end, inside its padding, where a followed row has its open-in-new icon.
            assertEquals(off.right - px(ROW_PADDING_DP), link.right, 1f)
            assertTrue("$link after $name", link.left > name.right)
            assertEquals(name.center.y, link.center.y, 1f)
        }

    @Test
    @Config(fontScale = 2f)
    fun atALargeFontTheLinkGoesUnderTheNameWithNoGap() =
        show {
            val off = row(AUDIOTEKA)
            val node = link()
            val link = node.boundsInRoot
            val name = name()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val text = layouts.single().size.height
            // Under the name, from its start; the target is centred on the text, so the text's top
            // is the name's bottom.
            assertEquals(name.left, link.left, 1f)
            assertEquals(name.bottom, link.top + (link.height - text) / 2, 1.5f)
            assertTrue("target ${link.height}", link.height >= px(TARGET_DP) - 1f)
            assertTrue("$link in $off", link.top >= off.top - 1f && link.bottom <= off.bottom + 1f)
        }

    private companion object {
        const val SPOTIFY = Settings.SPOTIFY
        const val AUDIOTEKA = "com.audioteka"
        const val ROW_DP = 56f
        const val TARGET_DP = 48f
        const val ROW_PADDING_DP = 8f
    }
}
