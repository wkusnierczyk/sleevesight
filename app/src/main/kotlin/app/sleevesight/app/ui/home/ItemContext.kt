package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.ItemInsights
import app.sleevesight.core.pipeline.SourcesHeld
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

// The song's, the artist's and the album's insights under *Key insights* (#522): a row each, under
// the key insight and before *Sources*, unfolding in place under *Expandable boxes* or opening a page
// of its own under *Separate pages*.

/**
 * What an item shown needs besides its state: the [track] it is of, the [settings] (*Reactive*),
 * whether the track would be written by itself now ([writesItself], `PipelineView.writesItself`),
 * the [notes] it is written by, where
 * a citation goes on the track's evidence ([cite]), where a report goes ([onReport]), *Read aloud*,
 * the note of a model found unavailable ([unavailable], [onModelSettings]), and *Retry* beside the
 * sources an item's lines come from that could not be reached ([retries], #1163).
 */
@Suppress("LongParameterList") // What an item reads and where its taps go, each on its own.
internal class ItemContext(
    val track: TrackState?,
    val settings: Settings,
    val notes: ItemNotes,
    val writesItself: Boolean,
    val cite: (String) -> Unit,
    val onReport: (TrackState, InsightState.Written, InsightItem) -> Unit,
    val readAloud: ReadAloud?,
    val unavailable: UnavailableModel? = null,
    val onModelSettings: () -> Unit = {},
    val sources: EvidenceSet? = null,
    val retries: Retries? = null,
) {
    /**
     * Whether *Reactive* is set to an automatic mode, with *Show insights* on: an item unfolded or on
     * its page is then asked for at once, as an opening when a tap opened it, else by itself, which
     * the coordinator's gates hold back as the track's own automatic writing is
     * (`PipelineHost.itemCallAllowed`).
     */
    val automatic: Boolean
        get() = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER

    /**
     * The tap on *Retry* beside [item]'s sources not reached, [evidence]'s: the track's asked again
     * ([ItemEvidence.retry], as the pipeline offers them, [retries]), and the item's own lookups gathered
     * again ([SourcesHeld.gatherAgain]: the album's, #1187); null when neither is offered.
     */
    fun retryOf(
        item: InsightItem,
        evidence: ItemEvidence,
    ): (() -> Unit)? {
        val shown = track
        val asks = retries?.ofAll(evidence.retry)
        val gathers =
            if (evidence.held?.gatherAgain == true && shown != null) {
                { notes.gatherAgain(shown, item) }
            } else {
                null
            }
        return when {
            asks == null -> gathers
            gathers == null -> asks
            else -> (
                {
                    asks()
                    gathers()
                }
            )
        }
    }
}

/**
 * The write of an item's opening by a tap under *On demand* (#948): [due] until the item's state is
 * known, then [taken], so it is asked for once; [NONE] where no tap opened it, or it is taken.
 */
internal class OpeningWrite(
    val due: Boolean,
    val taken: () -> Unit = {},
) {
    companion object {
        val NONE = OpeningWrite(due = false)
    }
}

/**
 * What [item] shows unfolded, or on its page: "by {model}" and *Read aloud* once written; the insight,
 * cited (a citation opens *Sources* at its line, the album's own lines among them, #522); *Report a
 * problem*. Before it is written: opened by a tap ([tapped]), written at once in every mode (#948),
 * *Writing…* with the turning record meanwhile: under an automatic mode as an opening
 * ([ItemContext.automatic]); under *On demand* once, the tap's consent, while [opening] is due, which
 * it ends as soon as the item's state is known, so a written, kept, stopped or failed one opens as it
 * is, and nothing asks again. Else, "Tap Write insight…" and *Write insight* with the **AI** mark,
 * whatever the mode and whatever held it back (a panel left open at a new track under *On demand*,
 * or one an automatic mode held back): an item says nothing of why (#948). The insight
 * another AI service, model or effort wrote, shown in place of none (#911), says so ("As written
 * previously.", with what *Rewrite insight* would rewrite it with), and *Rewrite insight*, with the
 * **AI** mark, rewrites it; one a rewrite wrote, on a tap or an automatic write in its place
 * (#972), says "Rewritten with …". With the model found unavailable (#540), that is said under a
 * written insight too, last, and *Rewrite insight* is *Write insight*, disabled.
 *
 * [shown] is what [item] shows, when its caller follows it already ([itemShown]): Home's panel, which
 * draws *Read aloud* in its heading ([ItemReadAloud], #894) and so passes [readAloud] false, leaving
 * it out of the byline's row. [afterRetry] runs after a tap on *Retry* beside sources not reached
 * (#1163), whose line then goes: it hands the focus on (to the panel's *Close*, the page's back
 * arrow), as the other panels' *Retry* does (#724).
 */
@Suppress("LongParameterList") // The item, what it is shown with, and how it was opened.
@Composable
internal fun ItemContent(
    item: InsightItem,
    context: ItemContext,
    tapped: Boolean,
    shown: ItemShown? = null,
    readAloud: Boolean = true,
    opening: OpeningWrite = OpeningWrite.NONE,
    afterRetry: () -> Unit = {},
) {
    val track = context.track
    if (track == null) {
        Gathering()
        return
    }
    val now = shown ?: itemShown(item, context)
    WritesOnTap(item, track, context, now, opening)
    val writingDue = opening.due && !context.automatic
    ItemShownContent(item, track, context, tapped, now, ItemShowing(readAloud, writingDue, afterRetry))
}

/**
 * Under *On demand*, the write of [item]'s opening by a tap (#948), while [opening] is due: asked for
 * once, the tap's consent ([ItemAsk.OPENED]), when [shown] says nothing is written
 * ([ItemShown.Offered]), with the app on screen; taken as soon as its state is known, whatever it is,
 * so a written, kept, stopped or failed one opens as it is, and neither a recomposition nor a return
 * to the screen asks again. Under an automatic mode it is taken at once: the opening is written as
 * one ([WritesAtOnce]).
 */
@Composable
private fun WritesOnTap(
    item: InsightItem,
    track: TrackState,
    context: ItemContext,
    shown: ItemShown,
    opening: OpeningWrite,
) {
    val due = opening.due
    val taken = opening.taken
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val onScreen = resumed.isAtLeast(Lifecycle.State.RESUMED)
    val known = shown != ItemShown.Gathering
    val offered = shown == ItemShown.Offered
    val automatic = context.automatic
    LaunchedEffect(due, known, offered, automatic, onScreen) {
        when {
            !due -> Unit
            automatic -> taken()
            known && !offered -> taken()
            offered && onScreen -> {
                taken()
                context.notes.write(track, item, ItemAsk.OPENED)
            }
        }
    }
}

/**
 * What [item] shows beside [context]'s track, as its notes give it: gathering while there is no
 * track. Followed again only when what the item depends on changes, not at each update of the track
 * (a streamed word of the key insight, its genres): the gathering is not asked again for those.
 */
@Composable
internal fun itemShown(
    item: InsightItem,
    context: ItemContext,
): ItemShown {
    val track = context.track ?: return ItemShown.Gathering
    val shown by remember(
        item,
        context.notes,
        track.normalized,
        track.nowPlaying,
        track.sources,
        track.resolution,
        track.gathered,
        track.evidence,
    ) { context.notes.item(track, item) }.collectAsState(ItemShown.Gathering)
    return shown
}

/**
 * *Read aloud* of [item]'s insight, once written ([shown]), under [modifier]: in the heading of its
 * panel on Home (#894), the panel's content leaving its own out; nothing before.
 */
@Composable
internal fun ItemReadAloud(
    item: InsightItem,
    context: ItemContext,
    shown: ItemShown,
    modifier: Modifier = Modifier,
) {
    val track = context.track ?: return
    val written = (shown as? ItemShown.Shown)?.state as? InsightState.Written ?: return
    itemReading(context.readAloud, item, track, written, context.notes)?.let {
        // In a box of its own, so [modifier] (the heading's alignment) reaches the row it sits in.
        Box(modifier) { ReadAloudButton(it, itemReadAloudTag(item)) }
    }
}

// How [ItemContent] shows an item: with *Read aloud* in its byline's row ([readAloud]); *Writing…* for
// one offered while its tap's write is due ([writingDue], #948); and where the focus goes after *Retry*
// beside sources not reached ([afterRetry], #1163).
private class ItemShowing(
    val readAloud: Boolean,
    val writingDue: Boolean,
    val afterRetry: () -> Unit,
)

// [ItemContent] of [track], [shown] as followed, as [showing] says: *Writing…* for one offered while
// its tap's write is due, asked for at once, rather than *Write insight* for a frame.
@Suppress("LongParameterList") // What the item shows, and where.
@Composable
private fun ItemShownContent(
    item: InsightItem,
    track: TrackState,
    context: ItemContext,
    tapped: Boolean,
    shown: ItemShown,
    showing: ItemShowing,
) {
    WritesAtOnce(item, track, context, tapped)
    val unavailable = context.unavailable
    val state = (shown as? ItemShown.Shown)?.state
    if (unavailable != null && state !is InsightState.Written && state !is InsightState.Writing) {
        ModelUnavailable(unavailable, context.onModelSettings)
        return
    }
    when (val now = shown) {
        ItemShown.Gathering -> Gathering()
        ItemShown.Off -> ItemQuiet(stringResource(R.string.home_insight_off))
        ItemShown.NoService -> ItemQuiet(stringResource(R.string.home_insight_unavailable))
        // Nothing written and nothing under way: *Write insight*, whatever the mode, so an automatic
        // write the gates refused never shows as writing; the plain line, whatever held it back.
        ItemShown.Offered ->
            if (showing.writingDue) {
                Writing()
            } else {
                ItemQuiet(stringResource(R.string.home_item_tap_write))
                WriteButton(item) { context.notes.write(track, item, ItemAsk.TAP) }
            }

        is ItemShown.Asking -> WritingItem(item) { context.notes.stop(now.key) }

        // Nothing to write about on this track (no album it names): said as for no evidence.
        ItemShown.NoSubject -> ItemQuiet(stringResource(noEvidence(item)))
        is ItemShown.Shown -> Shown(item, now, track, context, showing)
    }
}

/**
 * Under an automatic mode, [item]'s insight on [track] asked for at once ([ItemContext.automatic]):
 * as an opening when [tapped], else by itself; and asked again each time the app comes back on
 * screen, so a call the gates refused while it was away (no lookups in the background) is made once
 * they open. Asked again as the track's evidence changes while it stays open, one opened by a tap
 * asks as kept open ([ItemAsk.KEPT_OPEN], #1163), which the lines a *Retry* brings do not write.
 */
@Composable
private fun WritesAtOnce(
    item: InsightItem,
    track: TrackState,
    context: ItemContext,
    tapped: Boolean,
) {
    val atOnce = context.automatic
    val offered = ItemInsights.offered(track)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val onScreen = resumed.isAtLeast(Lifecycle.State.RESUMED)
    // The hold-back lifting (a tap on *Write* after *Saver*, a first action) asks again too.
    val holdBackLifted = context.writesItself
    // The evidence it first asked with on this track, while it stays open (#1163).
    val first = remember(track.normalized.lookupKey, item) { mutableStateOf<String?>(null) }
    LaunchedEffect(
        track.normalized.lookupKey,
        track.evidence?.fingerprint,
        item,
        atOnce,
        offered,
        onScreen,
        holdBackLifted,
    ) {
        if (atOnce && offered && onScreen) {
            val evidence = track.evidence?.fingerprint
            val asked = first.value
            if (asked == null) first.value = evidence
            val ask =
                when {
                    !tapped -> ItemAsk.BY_ITSELF
                    asked != null && asked != evidence -> ItemAsk.KEPT_OPEN
                    else -> ItemAsk.OPENED
                }
            context.notes.write(track, item, ask)
        }
    }
}

@Composable
private fun Shown(
    item: InsightItem,
    shown: ItemShown.Shown,
    track: TrackState,
    context: ItemContext,
    showing: ItemShowing,
) {
    val own = track.copy(evidence = shown.evidence.evidence)
    // Where a citation goes, and how it shows and is read: by the id *Sources* shows its line under.
    val onCite: (String) -> Unit = { id -> shownAs(id, shown.evidence, context.sources)?.let(context.cite) }
    val label: (String) -> String = { id -> shownAs(id, shown.evidence, context.sources) ?: id }
    when (val state = shown.state) {
        is InsightState.Writing -> {
            val prose =
                InsightContext(
                    track = own,
                    aiReady = true,
                    insightsOn = true,
                    modelAllowed = context.settings.isEnabled(SourceId.MODEL),
                    offers = InsightOffers(),
                )
            for (paragraph in paragraphsOf(state.partial)) UncheckedProse(paragraph, prose, onCite, label = label)
            WritingItem(item) { shown.key?.let(context.notes::stop) }
        }

        is InsightState.Written -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { shown.model?.let { InsightByline(it) } }
                if (showing.readAloud) {
                    itemReading(context.readAloud, item, track, state, context.notes)?.let {
                        ReadAloudButton(it, itemReadAloudTag(item))
                    }
                }
            }
            for (paragraph in state.grounded.paragraphs) {
                CheckedProse(paragraph, state.grounded, shown.evidence.evidence, onCite, label = label)
            }
            WrittenFooter(item, shown, state, track, context, label)
        }

        InsightState.NoEvidence -> NothingToWriteFrom(item, shown.evidence, context, showing.afterRetry)

        is InsightState.Failed ->
            RetryLine(
                // The artist's web search failed (#912): that, as the whole line, as *Ask AI* says it.
                if (state.reason == AiSearches.WEB_SEARCH_FAILED) {
                    stringResource(R.string.home_ai_web_search_failed)
                } else {
                    stringResource(R.string.home_item_failed, failureText(state.reason))
                },
                source = "",
                onRetry = { context.notes.write(track, item, ItemAsk.TAP) },
                modifier = Modifier.testTag(ITEM_FAILED_TAG),
                action = stringResource(R.string.home_ai_retry_action),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                outlined = true,
                // It starts another call: the AI mark, read as "Retry, uses AI" (#624).
                ai = true,
            )

        InsightState.Stopped -> StoppedItem(item) { context.notes.write(track, item, ItemAsk.TAP) }

        is InsightState.Idle -> Unit
    }
}

// The id *Sources* shows an item's line [id] under: the track's, or for the album's own line, the one
// *Sources* numbers it with among the track's (#522); null for none.
private fun shownAs(
    id: String,
    evidence: ItemEvidence,
    sources: EvidenceSet?,
): String? = shownId(id, evidence.evidence, sources) ?: evidence.trackIds[id]

// *Read aloud* of [item]'s insight [written], for [track].
@Composable
private fun itemReading(
    readAloud: ReadAloud?,
    item: InsightItem,
    track: TrackState,
    written: InsightState.Written,
    notes: ItemNotes,
): ReadAloudControl? =
    readAloudControl(readAloud, Reading.item(item.kind.key)) {
        readAloud?.readItem(track, item.kind.key, written, notes.item(track, item))
    }

/**
 * What the song's, the artist's and the album's insights (#522) read and where their taps go, on
 * Home and on their pages: a citation to [cite], as the key insight's, by the id *Sources* shows its
 * line under in [sources]; a report to [onReport], with the item it is of.
 */
@Suppress("LongParameterList") // What the items read, and where their taps go.
internal fun itemContext(
    state: NowPlayingState,
    shown: Showing,
    settings: Settings,
    actions: NowPlayingActions,
    cite: (String) -> Unit,
    sources: EvidenceSet?,
    onReport: (TrackState, InsightState.Written, InsightItem) -> Unit,
) = ItemContext(
    track = shown.track,
    sources = sources,
    settings = settings,
    notes = actions.itemNotes,
    writesItself = shown.view.writesItself,
    cite = cite,
    onReport = onReport,
    readAloud = actions.readAloud,
    retries = Retries(shown.view, shown.playing, actions.askAgain),
    unavailable = state.unavailable.takeIf { settings.writeInsights },
    onModelSettings = {
        state.unavailable?.let { actions.openSettingsAt(SettingsTarget.model(it.provider)) } ?: actions.openSettings()
    },
)
