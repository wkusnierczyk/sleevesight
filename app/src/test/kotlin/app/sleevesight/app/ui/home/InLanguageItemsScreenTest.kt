package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The line on what the artist's and the album's insights translated (#902) names each citation as its
 * chip does, by the id *Sources* shows its line under, not the item's own id: under *Always* an
 * artist's line in Hindi is W1 among the artist's lines, W3 among the track's.
 */
@RunWith(RobolectricTestRunner::class)
class InLanguageItemsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-10-17T12:00:00Z")

    private val singer =
        EvidenceDraft(
            SourceId.WIKIPEDIA,
            Scope.ARTIST,
            "उस्ताद राशिद ख़ान हिंदुस्तानी शास्त्रीय संगीत के गायक थे।",
            "https://hi.wikipedia.org/wiki/Rashid_Khan",
            at,
            language = "hi",
        )
    private val soundtrack =
        EvidenceDraft(
            SourceId.WIKIPEDIA,
            Scope.RELEASE,
            "इसका संगीत संदेश शांडिल्य ने दिया।",
            "https://hi.wikipedia.org/wiki/Jab_We_Met",
            at,
            language = "hi",
        )
    private val second = EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "It was a single.", null, at)

    // [shown] for [item], every other item offered; the album's evidence [album] for *Sources*.
    private fun notes(
        item: InsightItem,
        shown: ItemShown,
        album: EvidenceSet? = null,
    ): ItemNotes {
        opened = item
        return object : ItemNotes {
            override fun item(
                track: TrackState,
                item: InsightItem,
            ): Flow<ItemShown> = flowOf(if (item == opened) shown else ItemShown.Offered)

            override fun write(
                track: TrackState,
                item: InsightItem,
                ask: ItemAsk,
            ) = Unit

            override fun albumEvidence(track: TrackState): Flow<EvidenceSet?> = flowOf(album)
        }
    }

    // The item whose row is opened.
    private var opened: InsightItem = InsightItem.SONG

    private fun show(
        track: TrackState,
        notes: ItemNotes,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                HomeFixtures.sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(opened)).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun theArtistsLineNamesTheCitationAsSourcesShowsIt() {
        // The track's: MB1, W1, W2, and the singer's line in Hindi as W3.
        val trackLines = EvidenceSet.of(listOf(musicBrainzLine, wikipediaLine, second, singer), JvmHasher)
        // The artist's own: the singer's line alone, W1.
        val artistLines = EvidenceSet.of(listOf(singer), JvmHasher)
        val insight = written("He sang in the Hindustani classical tradition [W1].", from = artistLines)
        val shown = ItemShown.Shown(insight, ItemEvidence(artistLines, mapOf("W1" to "W3")), "a model")
        show(track(evidence = trackLines), notes(InsightItem.ARTIST, shown)) {
            assertEquals(1, count("Translated from Hindi: W3."))
            assertEquals(0, count("Translated from Hindi: W1."))
        }
    }

    @Test
    fun theAlbumsLineNamesTheCitationAsSourcesNumbersItAmongTheTracks() {
        // The album's own: an English line, W1, and the soundtrack's line in Hindi, W2; *Sources* lists
        // them after the track's W1, as W2 and W3.
        val albumLines =
            EvidenceSet.of(
                listOf(EvidenceDraft(SourceId.WIKIPEDIA, Scope.RELEASE, "It is a soundtrack.", null, at), soundtrack),
                JvmHasher,
            )
        val insight = written("Its music is by Sandesh Shandilya [W2].", from = albumLines)
        val shown = ItemShown.Shown(insight, ItemEvidence(albumLines, emptyMap()), "a model")
        show(track(), notes(InsightItem.ALBUM, shown, album = albumLines)) {
            assertEquals(1, count("Translated from Hindi: W3."))
            assertEquals(0, count("Translated from Hindi: W2."))
        }
    }
}
