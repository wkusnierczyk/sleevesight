package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.CardKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// The song's, the artist's and the album's notes as the rows' tests set them (#522, #948).

/** Each item's state, as the test sets it; the writes asked for, and the items followed. */
internal class FakeItemNotes : ItemNotes {
    val shown = MutableStateFlow<Map<InsightItem, ItemShown>>(emptyMap())
    val writes = mutableListOf<InsightItem>()

    override fun item(
        track: TrackState,
        item: InsightItem,
    ): Flow<ItemShown> = shown.map { it[item] ?: ItemShown.Offered }.also { followed += item }

    // Being written, as the test set it, whatever the track: the same artist's or album's on the next.
    override fun writes(
        track: TrackState,
        item: InsightItem,
    ): Flow<Boolean> =
        shown.map { now ->
            val state = now[item]
            state is ItemShown.Asking || (state as? ItemShown.Shown)?.state is InsightState.Writing
        }

    val asks = mutableListOf<ItemAsk>()
    val stops = mutableListOf<CardKey>()

    // The items followed, each time a flow of one is asked for.
    val followed = mutableListOf<InsightItem>()

    // Whether a write is taken up (asking, until its call), or refused by the gates (offered again).
    var takenUp = true

    // The album's own lines, once gathered (#522).
    val album = MutableStateFlow<EvidenceSet?>(null)

    override fun albumEvidence(track: TrackState): Flow<EvidenceSet?> = album

    // The artist's lines, once gathered: its web lines for an artist the sources miss (#912).
    val artist = MutableStateFlow<EvidenceSet?>(null)

    override fun artistEvidence(track: TrackState): Flow<EvidenceSet?> = artist

    override fun write(
        track: TrackState,
        item: InsightItem,
        ask: ItemAsk,
    ) {
        writes += item
        asks += ask
        if (takenUp) shown.value = shown.value + (item to ItemShown.Asking(ITEM_KEY))
    }

    override fun stop(key: CardKey) {
        stops += key
    }

    // The items whose own lookups were asked again (#1187), and what each then shows, if the test sets it.
    val gatheredAgain = mutableListOf<InsightItem>()
    var afterGathering: ItemShown? = null

    override fun gatherAgain(
        track: TrackState,
        item: InsightItem,
    ) {
        gatheredAgain += item
        afterGathering?.let { shown.value = shown.value + (item to it) }
    }
}

/** A card's key, as the notes would carry it. */
internal val ITEM_KEY =
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
