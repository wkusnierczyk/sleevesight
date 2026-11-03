package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's, the artist's and the album's rows under *Key insights* (#522): folded by default, each
 * unfolding in place under *Expandable boxes*, several at once, or opening its page under *Separate
 * pages*; written on a tap under *On demand*, at once under an automatic mode.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRowsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)
    private val automatic = Settings(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)

    // Each item's state, as the test sets it; the writes asked for.
    private class Notes : ItemNotes {
        val shown = MutableStateFlow<Map<InsightItem, ItemShown>>(emptyMap())
        val writes = mutableListOf<InsightItem>()

        override fun item(
            track: TrackState,
            item: InsightItem,
        ): Flow<ItemShown> = shown.map { it[item] ?: ItemShown.Offered }.also { followed += item }

        val asks = mutableListOf<ItemAsk>()
        val stops = mutableListOf<CardKey>()

        // The items followed, each time a flow of one is asked for.
        val followed = mutableListOf<InsightItem>()

        // Whether a write is taken up (asking, until its call), or refused by the gates (offered again).
        var takenUp = true

        // The album's own lines, once gathered (#522).
        val album = MutableStateFlow<EvidenceSet?>(null)

        override fun albumEvidence(track: TrackState): Flow<EvidenceSet?> = album

        override fun write(
            track: TrackState,
            item: InsightItem,
            ask: ItemAsk,
        ) {
            writes += item
            asks += ask
            if (takenUp) shown.value = shown.value + (item to ItemShown.Asking(KEY))
        }

        override fun stop(key: CardKey) {
            stops += key
        }
    }

    private val notes = Notes()

    private companion object {
        // A card's key, as the notes would carry it.
        val KEY =
            CardKey(
                CardKind.SONG,
                CardEntity.Track(normalizer.normalize(HomeFixtures.sundown), null),
                "f",
                1,
                "s",
                ProviderId.ANTHROPIC,
                "m",
                false,
                null,
            )
    }

    private val opened = mutableListOf<HomePage>()

    private fun state(
        settings: Settings,
        track: TrackState = track(),
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    private fun show(
        initial: NowPlayingState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(
                            current.value,
                            NowPlayingActions(normalizer::normalize, itemNotes = notes, openPage = { page ->
                                opened +=
                                    page
                            }),
                        )
                    }
                }
            }
            compose.waitForIdle()
            block(current)
        }
    }

    private fun row(item: InsightItem) = compose.onNodeWithTag(itemRowTag(item))

    @Test
    fun theRowsAreFoldedByDefaultEachItsKindAndName() =
        show(state(onDemand)) {
            compose.onAllNodesWithTag(ITEM_ROWS_TAG).assertCountEquals(1)
            val artist = row(InsightItem.ARTIST).performScrollTo().fetchSemanticsNode()
            assertEquals("Artist, Gordon Lightfoot", artist.config[SemanticsProperties.ContentDescription].single())
            assertEquals(text(R.string.home_fold_collapsed), artist.config[SemanticsProperties.StateDescription])
            assertEquals(text(R.string.home_item_expand), artist.config[SemanticsActions.OnClick].label)
            row(InsightItem.SONG).assertExistsWith("Song, Sundown")
            row(InsightItem.ALBUM).assertExistsWith("Album, Sundown")
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun underOnDemandAnUnfoldedRowOffersWriteInsightWithTheAiMarkAndATapWrites() =
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
            val button = compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).fetchSemanticsNode()
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_write)),
                button.config[SemanticsProperties.ContentDescription].single(),
            )
            assertTrue(notes.writes.isEmpty())
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            val unfolded = row(InsightItem.ARTIST).fetchSemanticsNode()
            assertEquals(text(R.string.home_fold_expanded), unfolded.config[SemanticsProperties.StateDescription])
        }

    @Test
    fun underAnAutomaticModeUnfoldingARowWritesItAtOnceAndSeveralUnfold() =
        show(state(automatic)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            row(InsightItem.ALBUM).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(InsightItem.SONG, InsightItem.ALBUM), notes.writes)
            // Opened by a tap: through the coordinator's gates, as a tap.
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.OPENED), notes.asks)
            compose.onAllNodesWithTag(ITEM_WRITING_TAG, useUnmergedTree = true).assertCountEquals(2)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun aWrittenItemShowsItsModelReadAloudItsInsightAndReportAProblem() {
        val artist = written("Gordon Lightfoot was a Canadian singer [W1].")
        notes.shown.value =
            mapOf(
                InsightItem.ARTIST to
                    ItemShown.Shown(artist, ItemEvidence(evidence, mapOf("W1" to "W1")), "Claude Haiku 4.5"),
            )
        show(state(onDemand)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText("by Claude Haiku 4.5").assertExists()
            compose.onNodeWithText("a Canadian singer", substring = true).assertExists()
            // The key insight has none written here: the one is the artist's.
            compose.onAllNodesWithTag(REPORT_TAG).assertCountEquals(1)
            compose.onNodeWithTag(itemReadAloudTag(InsightItem.ARTIST)).assertDoesNotExist()
        }
    }

    @Test
    fun anAlbumTheSourcesSayNothingAboutSaysSo() {
        notes.shown.value =
            mapOf(InsightItem.ALBUM to ItemShown.Shown(InsightState.NoEvidence, ItemEvidence(evidence, emptyMap())))
        show(state(onDemand)) {
            row(InsightItem.ALBUM).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_item_no_evidence_album)).assertExists()
        }
    }

    @Test
    fun underSeparatePagesATapOpensTheItemsPage() =
        show(state(onDemand.copy(layout = Layout.SEPARATE_PAGES))) {
            val artist = row(InsightItem.ARTIST).performScrollTo().fetchSemanticsNode()
            assertEquals(text(R.string.home_page_open), artist.config[SemanticsActions.OnClick].label)
            row(InsightItem.ARTIST).performClick()
            assertEquals(listOf<HomePage>(HomePage.Item(InsightItem.ARTIST.name)), opened)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }

    @Test
    fun aNewTrackStartsTheRowsAsTheSettingSays() {
        show(state(onDemand.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED))) { current ->
            // Unfolded on the first track, and on the next.
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(3)
            current.value = state(onDemand, track(carefree))
            compose.waitForIdle()
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(0)
        }
        show(state(onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT))) { current ->
            row(InsightItem.SONG).performScrollTo().performClick()
            current.value = state(onDemand.copy(itemsOnNewTrack = NewTrackFold.AS_LEFT), track(carefree))
            compose.waitForIdle()
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(1)
            assertEquals(
                text(R.string.home_fold_expanded),
                row(InsightItem.SONG).fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
        }
    }

    @Test
    fun anItemStartingUnfoldedIsAskedForByItselfAndOffersWriteInsightWhenTheGatesRefuse() {
        val unfolded = automatic.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED)
        // The gates (the first action, *Saver*, *On track skip (auto)*) refuse: offered again.
        notes.takenUp = false
        show(state(unfolded)) {
            compose.waitForIdle()
            assertEquals(setOf(ItemAsk.BY_ITSELF), notes.asks.toSet())
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(3)
        }
        notes.takenUp = true
        notes.writes.clear()
        notes.asks.clear()
        show(state(unfolded)) {
            compose.waitForIdle()
            assertEquals(InsightItem.entries.toSet(), notes.writes.toSet())
            assertEquals(setOf(ItemAsk.BY_ITSELF), notes.asks.toSet())
        }
    }

    @Test
    fun rowsLeftUnfoldedAskAgainWhenTheHoldBackLifts() {
        val unfolded = automatic.copy(itemsOnNewTrack = NewTrackFold.UNFOLDED)
        // Held back (*Saver*, the first action): the asks are refused, and the rows offer Write insight.
        notes.takenUp = false
        show(state(unfolded)) { current ->
            compose.waitForIdle()
            assertEquals(List(3) { ItemAsk.BY_ITSELF }, notes.asks)
            compose.onAllNodesWithText(text(R.string.home_item_tap_write)).assertCountEquals(3)
            // A tap on the key insight's Write lifts it: the coordinator would write the track by itself.
            notes.takenUp = true
            current.value =
                state(unfolded).let {
                    NowPlayingState(it.view.copy(writesItself = true), it.playing, it.playback, settings = unfolded)
                }
            compose.waitForIdle()
            assertEquals(List(6) { ItemAsk.BY_ITSELF }, notes.asks)
            compose.onAllNodesWithTag(ITEM_WRITING_TAG, useUnmergedTree = true).assertCountEquals(3)
        }
    }

    @Test
    fun anAutomaticWriteTheGatesRefuseOffersWriteInsightRatherThanWriting() {
        notes.takenUp = false
        show(state(automatic)) {
            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            compose.onAllNodesWithTag(ITEM_WRITING_TAG, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText(text(R.string.home_item_tap_write)).assertExists()
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.TAP), notes.asks)
        }
    }

    @Test
    fun aFailedItemSaysWhyAndItsRetryCarriesTheAiMark() {
        notes.shown.value =
            mapOf(
                InsightItem.SONG to
                    ItemShown.Shown(InsightState.Failed("timed_out"), ItemEvidence(evidence, emptyMap())),
            )
        show(state(onDemand)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.onNodeWithTag(ITEM_FAILED_TAG).performScrollTo().assertExists()
            val retry = compose.onNodeWithTag(RETRY_TAG).fetchSemanticsNode()
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_retry)),
                retry.config[SemanticsProperties.ContentDescription].single(),
            )
            compose
                .onNode(
                    hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(RETRY_TAG)),
                    useUnmergedTree = true,
                ).assertExists()
            compose.onNodeWithTag(RETRY_TAG).performClick()
            assertEquals(listOf(ItemAsk.TAP), notes.asks)
        }
    }

    @Test
    fun aWritingItemOffersStopAndAStoppedOneWritesAgainOnATap() {
        notes.shown.value =
            mapOf(
                InsightItem.SONG to
                    ItemShown.Shown(
                        InsightState.Writing("", model = "m"),
                        ItemEvidence(evidence, emptyMap()),
                        key = KEY,
                    ),
                InsightItem.ARTIST to ItemShown.Shown(InsightState.Stopped, ItemEvidence(evidence, emptyMap())),
            )
        show(state(onDemand)) {
            row(InsightItem.SONG).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_insight_writing_stop)).assertExists()
            compose.onNodeWithTag(itemStopTag(InsightItem.SONG)).performScrollTo().performClick()
            assertEquals(listOf(KEY), notes.stops)

            row(InsightItem.ARTIST).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_insight_stopped)).assertExists()
            compose.onNodeWithText(text(R.string.home_insight_retry)).assertExists()
            compose.onNodeWithTag(itemWriteTag(InsightItem.ARTIST)).performScrollTo().performClick()
            assertEquals(listOf(InsightItem.ARTIST), notes.writes)
            assertEquals(listOf(ItemAsk.TAP), notes.asks)
        }
    }

    @Test
    fun anItemIsFollowedOnceWhateverElseOfTheTrackChanges() {
        show(state(onDemand)) { current ->
            row(InsightItem.ALBUM).performScrollTo().performClick()
            compose.waitForIdle()
            // The key insight streaming: the track updated word by word.
            for (words in listOf("It", "It was", "It was recorded")) {
                current.value = state(onDemand, track(insight = InsightState.Writing(words, model = "m")))
                compose.waitForIdle()
            }
            assertEquals(listOf(InsightItem.ALBUM), notes.followed)
        }
    }

    @Test
    fun aPageLeftOpenCountsItsOpeningAsATapForItsTrackOnly() {
        val current = mutableStateOf(state(automatic))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    NowPlayingPageContent(
                        HomePage.Item(InsightItem.ARTIST.name),
                        current.value,
                        NowPlayingActions(normalizer::normalize, itemNotes = notes),
                    ) {}
                }
            }
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED), notes.asks)
            // The next track, the page still open: asked as one unfolded at its start, by itself.
            notes.shown.value = emptyMap()
            current.value = state(automatic, track(carefree))
            compose.waitForIdle()
            assertEquals(listOf(ItemAsk.OPENED, ItemAsk.BY_ITSELF), notes.asks)
        }
    }

    @Test
    fun anAlbumCitationOpensSourcesAtTheAlbumsLineAmongTheTracks() {
        val album =
            EvidenceSet.of(
                listOf(
                    draft(
                        SourceId.WIKIPEDIA,
                        "Sundown is his tenth album.",
                        "https://en.wikipedia.org/wiki/S",
                        Scope.RELEASE,
                    ),
                ),
                JvmHasher,
            )
        notes.album.value = album
        val reply =
            GroundingCheck.check(
                "It is his tenth album [W1].",
                album,
                allowModel = false,
            ) as InsightReply.Written
        notes.shown.value =
            mapOf(
                InsightItem.ALBUM to
                    ItemShown.Shown(
                        InsightState.Written(reply.grounded, cached = false),
                        ItemEvidence(album, emptyMap()),
                    ),
            )
        show(state(onDemand)) {
            row(InsightItem.ALBUM).performScrollTo().performClick()
            // Shown, and read, by the id Sources shows the line under: W2, not the insight's own W1.
            val prose = compose.onNodeWithText("tenth album", substring = true).performScrollTo().fetchSemanticsNode()
            val text = prose.config[SemanticsProperties.Text].joinToString { it.text }
            assertTrue(text, "\u2009W2\u2009" in text && "W1" !in text)
            val spoken = prose.config[SemanticsProperties.ContentDescription].single()
            assertTrue(spoken, app.getString(R.string.home_cite, "W2") in spoken)
            compose.onNodeWithText("tenth album", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "W1"
            }
            compose.waitForIdle()
            // The track has W1 from Wikipedia: the album's line is shown as W2, about the album.
            compose.onNodeWithTag(LINE_PREFIX + "W2").performScrollTo().assertExists()
            compose.onNodeWithText("album · en.wikipedia.org", substring = true, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertExists()
        }
    }

    @Test
    fun anAlbumCitationOnSourcesPageWaitsForTheAlbumsLinesAndReachesItBeyondTheFirstScreenful() {
        val mb = (1..10).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val long =
            track(
                sources = mapOf(SourceId.MUSICBRAINZ to HomeFixtures.done(evidence = mb)),
                evidence = EvidenceSet.of(mb, JvmHasher),
            )
        val album =
            EvidenceSet.of(
                listOf(draft(SourceId.MUSICBRAINZ, "“Sundown” is an album.", scope = Scope.RELEASE)),
                JvmHasher,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    NowPlayingPageContent(
                        // The album's line, shown as MB11 after the track's ten.
                        HomePage.Sources(cite = "MB11"),
                        state(onDemand, long),
                        NowPlayingActions(normalizer::normalize, itemNotes = notes),
                    ) {}
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "MB11").assertDoesNotExist()
            // The album's lines read after the page opened.
            notes.album.value = album
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "MB11").performScrollTo().assertExists()
        }
    }

    @Test
    fun aCitationOfTheTracksLineOnSourcesPageFollowsItWhenTheAlbumsLinesMoveItDown() {
        val mb = (1..15).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val w = listOf(draft(SourceId.WIKIPEDIA, "Sundown is a song."))
        val long =
            track(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to HomeFixtures.done(evidence = mb),
                        SourceId.WIKIPEDIA to HomeFixtures.done(evidence = w),
                    ),
                evidence = EvidenceSet.of(mb + w, JvmHasher),
            )
        val album =
            EvidenceSet.of(
                listOf(draft(SourceId.MUSICBRAINZ, "“Sundown” is an album.", scope = Scope.RELEASE)),
                JvmHasher,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    NowPlayingPageContent(
                        // The track's own W1, the 16th entry: two screenfuls.
                        HomePage.Sources(cite = "W1"),
                        state(onDemand, long),
                        NowPlayingActions(normalizer::normalize, itemNotes = notes),
                    ) {}
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            // The album's MusicBrainz line comes in before it: W1 is now the 17th, past two screenfuls.
            notes.album.value = album
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "MB16").assertExists()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
        }
    }

    @Test
    fun aCitationOnSourcesPageIsNotFollowedOntoTheNextTracksLineOfTheSameId() {
        val first = state(onDemand)
        // The next track: sixteen MusicBrainz lines, then its own W1, past the first screenful.
        val mb = (1..16).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val w = listOf(draft(SourceId.WIKIPEDIA, "Carefree Highway is a song."))
        val next =
            track(
                carefree,
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to HomeFixtures.done(evidence = mb),
                        SourceId.WIKIPEDIA to HomeFixtures.done(evidence = w),
                    ),
                evidence = EvidenceSet.of(mb + w, JvmHasher),
            )
        val current = mutableStateOf(first)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    NowPlayingPageContent(
                        HomePage.Sources(cite = "W1"),
                        current.value,
                        NowPlayingActions(normalizer::normalize, itemNotes = notes),
                    ) {}
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
            current.value = state(onDemand, next)
            compose.waitForIdle()
            // At its first screenful: the next track's W1, the 17th line, is not unfolded to.
            compose.onNodeWithTag(LINE_PREFIX + "MB8").assertExists()
            compose.onNodeWithTag(LINE_PREFIX + "MB9").assertDoesNotExist()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertDoesNotExist()
        }
    }

    @Test
    fun theAlbumsLinesComingInKeepTheSourcesHiddenWithTheChips() {
        show(state(onDemand)) {
            compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(CHIP_PREFIX + SourceId.WIKIPEDIA.tag).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertDoesNotExist()
            // The album's insight gathers its lines: Wikipedia's, the album's among them, stay hidden.
            notes.album.value =
                EvidenceSet.of(
                    listOf(draft(SourceId.WIKIPEDIA, "Sundown is his tenth album.", scope = Scope.RELEASE)),
                    JvmHasher,
                )
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertDoesNotExist()
            compose.onNodeWithTag(LINE_PREFIX + "W2").assertDoesNotExist()
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertExists()
        }
    }

    private fun SemanticsNodeInteraction.assertExistsWith(description: String) {
        assertEquals(
            description,
            performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
        )
    }
}
