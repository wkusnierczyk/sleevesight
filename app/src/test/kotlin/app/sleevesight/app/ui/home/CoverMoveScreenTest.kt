package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.core.resolution.MbTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cover's back kept as it was left when the cover moves to another place on Home (#1097: the
 * phone turned across 600 dp, the cover going from Home's column to a side of its own and back): turned
 * over, on the disc shown, and its list scrolled where it was, as Home keeps it ([rememberCoverMemory]),
 * above where the cover is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class CoverMoveScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun disc(
        position: Int,
        vararg titles: String,
    ) = BackDisc(position, titles.mapIndexed { i, title -> MbTrack(i + 1, title, (300 + i) * 1000L, "rec-$i") })

    // Playing the first disc's third song; the second disc is the later one.
    private val twoDiscs =
        CoverBack.Found(
            listOf(disc(1, *Array(17) { "One $it" }), disc(2, *Array(30) { "Two $it" })),
            TrackAt(0, 2),
        )

    // Whether the cover is drawn in its other place: in portrait's column or on a side of its own.
    private var moved by mutableStateOf(false)

    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    // The phone turned: the screen saved, dropped, and composed afresh with the cover in its other place.
    private fun turn() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        moved = !moved
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun shown(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (!emitted) return@setContent
                    CompositionLocalProvider(
                        LocalAnimatorDurationScale provides 0f,
                        LocalSaveableStateRegistry provides registry,
                    ) {
                        // Kept above both places, as `NowPlayingContent` keeps it.
                        val memory = rememberCoverMemory()
                        val turn =
                            CoverTurn(
                                album = "two discs",
                                back = twoDiscs,
                                key = TracklistKey("g"),
                                look = {},
                                retry = {},
                                openSources = {},
                                colors = MaterialTheme.colorScheme,
                                memory = memory,
                            )
                        if (moved) {
                            Box { Box { ArtAndProgress(null, PlaybackInfo.NONE, null, turn, fitHeight = true) } }
                        } else {
                            Column { ArtAndProgress(null, PlaybackInfo.NONE, null, turn) }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun theTurnedCoverKeepsItsDiscAndItsScrollWhenItMoves() =
        shown {
            compose.onNodeWithTag(COVER_TAG).performClick()
            // The later disc, scrolled down its list.
            compose.onNodeWithContentDescription("Disc 2").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(TRACKLIST_TAG).performScrollToIndex(TWENTY)
            compose.waitForIdle()
            track("21, Two 20").assertIsDisplayed()
            repeat(2) {
                turn()
                compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
                compose.onNodeWithContentDescription("Disc 2").assertIsSelected()
                track("21, Two 20").assertIsDisplayed()
                track("1, Two 0").assertIsNotDisplayed()
            }
        }

    // A track's row by the start of what it reads.
    private fun track(start: String) = compose.onNode(hasContentDescription(start, substring = true))

    private companion object {
        const val TWENTY = 20
    }
}
