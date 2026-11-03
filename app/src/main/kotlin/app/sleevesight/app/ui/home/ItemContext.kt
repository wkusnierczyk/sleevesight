package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import app.sleevesight.app.ui.report.ReportAction
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.ItemInsights
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
 * and the note of a model found unavailable ([unavailable], [onModelSettings]).
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
) {
    /**
     * Whether *Reactive* is set to an automatic mode, with *Show insights* on: an item unfolded or on
     * its page is then asked for at once, as an opening when a tap opened it, else by itself, which
     * the coordinator's gates hold back as the track's own automatic writing is
     * (`PipelineHost.itemCallAllowed`).
     */
    val automatic: Boolean
        get() = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER
}

/**
 * What [item] shows unfolded, or on its page: "by {model}" and *Read aloud* once written; the insight,
 * cited (a citation opens *Sources* at its line, or a line of the album's own sources, which *Sources*
 * does not list, at its page); *Report a problem*. Before it is written: under *On demand*, "Tap Write
 * insight…" and *Write insight* with the **AI** mark; under an automatic mode, written at once
 * ([ItemContext.automatic], [tapped]), *Writing…* with the turning record meanwhile.
 */
@Composable
internal fun ItemContent(
    item: InsightItem,
    context: ItemContext,
    tapped: Boolean,
) {
    val track = context.track
    if (track == null) {
        Gathering()
        return
    }
    // Followed again only when what the item depends on changes, not at each update of the track (a
    // streamed word of the key insight, its genres): the gathering is not asked again for those.
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
        // write the gates refused never shows as writing.
        ItemShown.Offered -> {
            ItemQuiet(stringResource(R.string.home_item_tap_write))
            WriteButton(item) { context.notes.write(track, item, ItemAsk.TAP) }
        }

        is ItemShown.Asking -> WritingItem(item) { context.notes.stop(now.key) }

        // Nothing to write about on this track (no album it names): said as for no evidence.
        ItemShown.NoSubject -> ItemQuiet(stringResource(noEvidence(item)))
        is ItemShown.Shown -> Shown(item, now, track, context)
    }
}

/**
 * Under an automatic mode, [item]'s insight on [track] asked for at once ([ItemContext.automatic]):
 * as an opening when [tapped], else by itself; and asked again each time the app comes back on
 * screen, so a call the gates refused while it was away (no lookups in the background) is made once
 * they open.
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
            context.notes.write(track, item, if (tapped) ItemAsk.OPENED else ItemAsk.BY_ITSELF)
        }
    }
}

@Composable
private fun Shown(
    item: InsightItem,
    shown: ItemShown.Shown,
    track: TrackState,
    context: ItemContext,
) {
    val own = track.copy(evidence = shown.evidence.evidence)
    val onCite = citeOf(shown.evidence, context.cite)
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
            for (paragraph in paragraphsOf(state.partial)) UncheckedProse(paragraph, prose, onCite)
            WritingItem(item) { shown.key?.let(context.notes::stop) }
        }

        is InsightState.Written -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { shown.model?.let { InsightByline(it) } }
                itemReading(context.readAloud, item, track, state, context.notes)?.let {
                    ReadAloudButton(it, itemReadAloudTag(item))
                }
            }
            for (paragraph in state.grounded.paragraphs) {
                CheckedProse(paragraph, state.grounded, shown.evidence.evidence, onCite)
            }
            GroundingLine(state.grounded, details = false)
            if (!state.complete) ItemQuiet(stringResource(R.string.home_insight_incomplete))
            ReportAction { context.onReport(own, state, item) }
        }

        InsightState.NoEvidence -> ItemQuiet(stringResource(noEvidence(item)))

        is InsightState.Failed ->
            RetryLine(
                stringResource(R.string.home_item_failed, failureText(state.reason)),
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

// Where a citation of an item's line goes: the track's line in *Sources*, where it is one; else its
// page, for a line of the album's own sources, which *Sources* does not list.
@Composable
private fun citeOf(
    evidence: ItemEvidence,
    cite: (String) -> Unit,
): (String) -> Unit {
    val uris = LocalUriHandler.current
    return { id ->
        val own = evidence.trackIds[id]
        val url = evidence.evidence[id]?.url
        when {
            own != null -> cite(own)
            url != null -> runCatching { uris.openUri(url) }
        }
    }
}

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
 * Home and on their pages: a citation to [cite], as the key insight's; a report to [onReport], with
 * the item it is of.
 */
@Suppress("LongParameterList") // What the items read, and where their taps go.
internal fun itemContext(
    state: NowPlayingState,
    shown: Showing,
    settings: Settings,
    actions: NowPlayingActions,
    cite: (String) -> Unit,
    onReport: (TrackState, InsightState.Written, InsightItem) -> Unit,
) = ItemContext(
    track = shown.track,
    settings = settings,
    notes = actions.itemNotes,
    writesItself = shown.view.writesItself,
    cite = cite,
    onReport = onReport,
    readAloud = actions.readAloud,
    unavailable = state.unavailable.takeIf { settings.writeInsights },
    onModelSettings = {
        state.unavailable?.let { actions.openSettingsAt(SettingsTarget.model(it.provider)) } ?: actions.openSettings()
    },
)
