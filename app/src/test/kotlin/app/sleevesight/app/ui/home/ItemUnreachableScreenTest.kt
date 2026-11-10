package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The artist's insight with nothing to write from while MusicBrainz could not be reached (#1163):
 * its panel says so, with *Retry*, which asks MusicBrainz again, rather than that the sources give
 * nothing; with every source answered, it says the sources give nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ItemUnreachableScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val notes = FakeItemNotes()

    private val retried = mutableListOf<Triple<RunKey, Set<SourceId>, NowPlaying>>()

    private val unreached =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to done(Outcome.Unreachable),
                    SourceId.WIKIPEDIA to done(evidence = listOf(wikipediaLine)),
                ),
        )

    private fun show(unanswered: List<SourceId>) {
        val evidence = ItemEvidence(EvidenceSet.of(emptyList(), JvmHasher), emptyMap(), unanswered = unanswered)
        notes.shown.value = mapOf(InsightItem.ARTIST to ItemShown.Shown(InsightState.NoEvidence, evidence))
        val view =
            PipelineView(
                unreached,
                aiReady = true,
                askAgainKey = RunKey.of(normalizer, sundown),
                askable = setOf(SourceId.MUSICBRAINZ),
            )
        val state =
            NowPlayingState(
                view,
                unreached.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                itemNotes = notes,
                askAgain = { key, sources, shown -> retried += Triple(key, sources, shown) },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
            compose.waitForIdle()
            if (unanswered.isEmpty()) {
                compose.onNodeWithText(app.getString(R.string.home_item_no_evidence_artist)).assertExists()
                compose.onNodeWithTag(ITEM_UNREACHABLE_TAG).assertDoesNotExist()
            } else {
                compose.onNodeWithText(app.getString(R.string.home_item_no_evidence_artist)).assertDoesNotExist()
                compose.onNodeWithText("MusicBrainz could not be reached.").assertExists()
                compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performScrollTo().performClick()
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun musicBrainzNotReachedIsNamedWithRetryWhichAsksItAgain() {
        show(listOf(SourceId.MUSICBRAINZ))
        assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), setOf(SourceId.MUSICBRAINZ), sundown)), retried)
    }

    @Test
    fun withEverySourceAnsweredItSaysTheSourcesGiveNothing() {
        show(emptyList())
        assertEquals(emptyList<Any>(), retried)
    }
}
