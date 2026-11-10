package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Under an automatic mode, a panel opened by a tap asks for its insight as an opening, and, left
 * open, asks again as the track's evidence changes as kept open (#1163): what a *Retry* of a source
 * brings then starts no call by itself (`CoordinatorState.itemCallAllowed`), while a tap's opening
 * still writes; so too, asked again as the app comes back on screen (#1187).
 */
@RunWith(RobolectricTestRunner::class)
class ItemKeptOpenScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val notes = FakeItemNotes().apply { takenUp = false }

    private val automatic = Settings(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)

    private fun state(track: TrackState) =
        NowPlayingState(
            PipelineView(track, aiReady = true),
            track.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = automatic,
        )

    @Test
    fun aPanelLeftOpenAsksAgainAsKeptOpenWhenTheEvidenceChanges() {
        val before = track(evidence = EvidenceSet.of(listOf(musicBrainzLine), JvmHasher))
        // A *Retry* of MusicBrainz brings the artist's line: the same track, other evidence.
        val band = draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST)
        val after = track(evidence = EvidenceSet.of(listOf(musicBrainzLine, band), JvmHasher))
        val current = mutableStateOf(state(before))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)

            current.value = state(after)
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.KEPT_OPEN), notes.asks)
        }
    }

    @Test
    fun aPanelLeftOpenAsksAgainAsKeptOpenWhenTheAppComesBackOnScreen() {
        // #1187: the lines an album's *Retry* brought, the evidence unchanged; the app left and come
        // back to. Not an opening again, which a *Retry* would not hold.
        val shown = state(track(evidence = EvidenceSet.of(listOf(musicBrainzLine), JvmHasher)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(shown, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.ALBUM)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)

            // Paused (another window over it), then back: off screen and on, as `WritesAtOnce` reads it.
            scenario.moveToState(Lifecycle.State.STARTED)
            compose.waitForIdle()
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.KEPT_OPEN), notes.asks)
        }
    }
}
