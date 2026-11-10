package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
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
import app.sleevesight.core.pipeline.SourcesHeld
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
 * nothing; with every source answered, it says the sources give nothing. The album's alike (#1187),
 * its own lookups not reached gathered again by *Retry*, the track's MusicBrainz asked again.
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

    private fun show(
        unanswered: List<SourceId>,
        item: InsightItem = InsightItem.ARTIST,
        held: SourcesHeld? = unanswered.takeIf { it.isNotEmpty() }?.let(::SourcesHeld),
        block: () -> Unit = { checkLine(unanswered, item) },
    ) {
        val evidence = ItemEvidence(EvidenceSet.of(emptyList(), JvmHasher), emptyMap(), held = held)
        notes.shown.value = mapOf(item to ItemShown.Shown(InsightState.NoEvidence, evidence))
        // Gathered again: the line gives way, as once the album's lookups answer.
        notes.afterGathering = ItemShown.Offered
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
                askAgain = { key, sources, shown ->
                    retried += Triple(key, sources, shown)
                    // Asked again: the line gives way, as once the sources are gathered anew.
                    notes.shown.value = emptyMap()
                },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(item)).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun checkLine(
        unanswered: List<SourceId>,
        item: InsightItem,
    ) {
        val nothing = app.getString(noEvidence(item))
        if (unanswered.isEmpty()) {
            compose.onNodeWithText(nothing).assertExists()
            compose.onNodeWithTag(ITEM_UNREACHABLE_TAG).assertDoesNotExist()
        } else {
            compose.onNodeWithText(nothing).assertDoesNotExist()
            val names = if (unanswered.size == 1) "MusicBrainz" else "MusicBrainz and Wikipedia"
            compose.onNodeWithText("$names could not be reached.").assertExists()
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun musicBrainzNotReachedIsNamedWithRetryWhichAsksItAgain() {
        show(listOf(SourceId.MUSICBRAINZ))
        assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), setOf(SourceId.MUSICBRAINZ), sundown)), retried)
    }

    @Test
    fun fromAKeyboardRetryHandsTheFocusToClose() {
        show(listOf(SourceId.MUSICBRAINZ)) {
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performScrollTo().requestFocus()
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            // The line gone with *Retry*: the focus on the panel's *Close*, not fallen to the top.
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).assertDoesNotExist()
            compose.onNodeWithTag("${itemPanelTag(InsightItem.ARTIST)}:close").assertIsFocused()
        }
        assertEquals(setOf(SourceId.MUSICBRAINZ), retried.single().second)
    }

    @Test
    fun withEverySourceAnsweredItSaysTheSourcesGiveNothing() {
        show(emptyList())
        assertEquals(emptyList<Any>(), retried)
    }

    @Test
    fun theAlbumsOwnLookupNotReachedIsNamedWithRetryWhichGathersItAgain() {
        val held = SourcesHeld(listOf(SourceId.WIKIPEDIA), retry = emptyList(), gatherAgain = true)
        show(listOf(SourceId.WIKIPEDIA), InsightItem.ALBUM, held) {
            compose.onNodeWithText(app.getString(R.string.home_item_no_evidence_album)).assertDoesNotExist()
            compose.onNodeWithText("Wikipedia could not be reached.").assertExists()
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(ITEM_UNREACHABLE_TAG).assertDoesNotExist()
        }
        // The album's lookups asked again, not the track's sources, which answered.
        assertEquals(listOf(InsightItem.ALBUM), notes.gatheredAgain)
        assertEquals(emptyList<Any>(), retried)
        // A *Retry* of sources writes nothing by itself.
        assertEquals(emptyList<InsightItem>(), notes.writes)
    }

    @Test
    fun theTracksMusicBrainzAndTheAlbumsWikipediaAreAskedAgainTogether() {
        val held =
            SourcesHeld(
                listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA),
                listOf(SourceId.MUSICBRAINZ),
                gatherAgain = true,
            )
        show(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), InsightItem.ALBUM, held)
        assertEquals(setOf(SourceId.MUSICBRAINZ), retried.single().second)
        assertEquals(listOf(InsightItem.ALBUM), notes.gatheredAgain)
    }

    @Test
    fun fromAKeyboardTheAlbumsRetryHandsTheFocusToClose() {
        val held = SourcesHeld(listOf(SourceId.MUSICBRAINZ), gatherAgain = false)
        show(listOf(SourceId.MUSICBRAINZ), InsightItem.ALBUM, held) {
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performScrollTo().requestFocus()
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            compose.onNodeWithTag(ITEM_UNREACHABLE_RETRY_TAG).assertDoesNotExist()
            compose.onNodeWithTag("${itemPanelTag(InsightItem.ALBUM)}:close").assertIsFocused()
        }
        assertEquals(setOf(SourceId.MUSICBRAINZ), retried.single().second)
        assertEquals(emptyList<InsightItem>(), notes.gatheredAgain)
    }

    @Test
    fun theAlbumWithEverySourceAnsweredSaysTheSourcesGiveNothing() {
        show(emptyList(), InsightItem.ALBUM)
        assertEquals(emptyList<Any>(), retried)
        assertEquals(emptyList<InsightItem>(), notes.gatheredAgain)
    }
}
