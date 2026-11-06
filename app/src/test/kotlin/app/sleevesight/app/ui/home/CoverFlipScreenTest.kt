package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.core.resolution.MbTrack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cover that turns over to show the album's tracklist (#984): a tap turns it, asking for the
 * album's tracklist; a tap anywhere on the back turns it back, a scroll does not; the playing song
 * read as playing; several discs a page each, opening on the playing song's, the heading read "Disc 2
 * of 2", a dot going to another disc; why there is no tracklist in *Dates*' words; another album
 * turning it back to the cover; and the screen reader's names and actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h891dp-420dpi")
class CoverFlipScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val looked = mutableListOf<String>()

    private fun disc(vararg titles: String) =
        BackDisc(titles.mapIndexed { i, title -> MbTrack(i + 1, title, (300 + i) * 1000L, "rec-$i") })

    private val kindOfBlue =
        CoverBack.Found(
            listOf(disc("So What", "Freddie Freeloader", "Blue in Green", "All Blues", "Flamenco Sketches")),
            TrackAt(0, 2),
        )

    private val twoDiscs =
        CoverBack.Found(
            listOf(disc(*Array(17) { "One $it" }), disc(*Array(13) { "Two $it" })),
            TrackAt(1, 5),
        )

    private var album by mutableStateOf<String?>("kind of blue")
    private var back by mutableStateOf<CoverBack>(CoverBack.Looking)

    // The art and its back alone, the animations as [scale] sets them.
    private fun shown(
        initial: CoverBack,
        scale: Float = 0f,
        block: () -> Unit,
    ) {
        back = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalAnimatorDurationScale provides scale) {
                        val turn =
                            CoverTurn(
                                album = album,
                                back = back,
                                group = "g",
                                look = { group -> looked += group },
                                retry = {},
                                openSources = {},
                                colors = MaterialTheme.colorScheme,
                            )
                        ArtAndProgress(null, PlaybackInfo.NONE, null, turn)
                    }
                }
            }
            block()
        }
    }

    private fun cover() = compose.onNodeWithTag(COVER_TAG)

    private fun description() = cover().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private fun action() = cover().fetchSemanticsNode().config[SemanticsActions.OnClick].label

    @Test
    fun aTapTurnsTheCoverAndAsksForTheTracklistOnce() =
        shown(kindOfBlue) {
            assertEquals(text(R.string.home_art), description())
            assertEquals(text(R.string.home_art_show_tracklist), action())
            assertEquals(emptyList<String>(), looked)
            cover().performClick()
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            assertEquals(text(R.string.home_tracklist), description())
            assertEquals(text(R.string.home_tracklist_show_cover), action())
            assertEquals(listOf("g"), looked)
            cover().performClick()
            compose.onNodeWithTag(COVER_BACK_TAG).assertDoesNotExist()
            assertEquals(text(R.string.home_art), description())
        }

    @Test
    fun aTapOnATrackTurnsBackAndAScrollDoesNot() =
        shown(twoDiscs) {
            cover().performClick()
            compose.onNodeWithContentDescription("6, Two 5, 5 minutes 5 seconds, playing").performClick()
            compose.onNodeWithTag(COVER_BACK_TAG).assertDoesNotExist()
            cover().performClick()
            compose.onNodeWithTag(TRACKLIST_TAG).performTouchInput { swipeUp() }
            compose.waitForIdle()
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
        }

    @Test
    fun thePlayingSongReadsAsPlayingAndEachTrackWithItsLength() =
        shown(kindOfBlue) {
            cover().performClick()
            compose.onNodeWithContentDescription("3, Blue in Green, 5 minutes 2 seconds, playing").assertExists()
            compose.onNodeWithContentDescription("1, So What, 5 minutes").assertExists()
        }

    @Test
    fun severalDiscsOpenOnThePlayingSongsWithItsHeadingAndADotGoesToAnother() =
        shown(twoDiscs) {
            cover().performClick()
            val heading = compose.onNodeWithContentDescription("Disc 2 of 2").fetchSemanticsNode()
            assertEquals(Unit, heading.config.getOrNull(SemanticsProperties.Heading))
            compose.onNodeWithText("Two 5", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Disc 2").assertIsSelected()
            compose.onNodeWithContentDescription("Disc 1").performClick()
            compose.onNodeWithContentDescription("Disc 1 of 2").assertExists()
            compose.onNodeWithContentDescription("Disc 1").assertIsSelected()
            // The dot's tap is its own: the back stays.
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
        }

    @Test
    fun aSingleDiscHasNoHeading() =
        shown(kindOfBlue) {
            cover().performClick()
            compose.onNodeWithText("Disc 1").assertDoesNotExist()
        }

    @Test
    fun withNoTracklistTheBackSaysWhyInDatesWords() {
        val cases =
            listOf(
                CoverBack.NotFound to R.string.home_tracklist_none,
                CoverBack.Looking to R.string.home_musicbrainz_looking,
                CoverBack.Off to R.string.home_musicbrainz_off,
                CoverBack.Unreachable("g") to R.string.home_musicbrainz_unreachable,
            )
        shown(CoverBack.NotFound) {
            cover().performClick()
            for ((why, line) in cases) {
                back = why
                compose.onNodeWithText(text(line)).assertExists()
            }
            compose.onNodeWithText(text(R.string.home_retry)).assertExists()
        }
    }

    @Test
    fun anotherAlbumTurnsBackToTheCoverTheSameAlbumKeepsTheBack() =
        shown(kindOfBlue) {
            cover().performClick()
            back = kindOfBlue.copy(playing = TrackAt(0, 3))
            compose.onNodeWithContentDescription("4, All Blues, 5 minutes 3 seconds, playing").assertExists()
            album = "ok computer"
            compose.waitForIdle()
            compose.onNodeWithTag(COVER_BACK_TAG).assertDoesNotExist()
            assertEquals(text(R.string.home_art), description())
        }

    @Test
    fun withAnimationsTheTurnTakesHalfASecond() =
        shown(kindOfBlue, scale = 1f) {
            cover().assertExists()
            compose.mainClock.autoAdvance = false
            cover().performClick()
            compose.mainClock.advanceTimeBy(FLIP_MS / 4L)
            compose.onNodeWithTag(COVER_BACK_TAG).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(FLIP_MS.toLong())
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
        }
}
