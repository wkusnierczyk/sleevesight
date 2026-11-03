package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.logo.Busy
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
 * What an item shown needs besides its state: the [track] it is of, the [view] and [settings]
 * (*Reactive*, and whether the track's own writing is held back), the [notes] it is written by, where
 * a citation goes on the track's evidence ([cite]), where a report goes ([onReport]), *Read aloud*,
 * and the note of a model found unavailable ([unavailable], [onModelSettings]).
 */
@Suppress("LongParameterList") // What an item reads and where its taps go, each on its own.
internal class ItemContext(
    val track: TrackState?,
    val view: PipelineView,
    val settings: Settings,
    val notes: ItemNotes,
    val cite: (String) -> Unit,
    val onReport: (TrackState, InsightState.Written, InsightItem) -> Unit,
    val readAloud: ReadAloud?,
    val unavailable: UnavailableModel? = null,
    val onModelSettings: () -> Unit = {},
) {
    /** Whether *Reactive* is set to an automatic mode, with *Show insights* on. */
    val automatic: Boolean
        get() = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER

    /**
     * Whether [item], unfolded or on its page, is written at once: under an automatic mode, when a tap
     * opened it ([tapped]), or else, unfolded at the track's start, when nothing holds the track's own
     * writing back (no first action yet, *Saver*, a track that followed the last one by itself under
     * *On track skip (auto)*: [PipelineView.waiting], [PipelineView.lapsed], [PipelineView.followed]).
     */
    fun writesAtOnce(tapped: Boolean): Boolean =
        automatic && (tapped || !(view.waiting || view.lapsed || view.followed))
}

/**
 * What [item] shows unfolded, or on its page: "by {model}" and *Read aloud* once written; the insight,
 * cited (a citation opens *Sources* at its line, or a line of the album's own sources, which *Sources*
 * does not list, at its page); *Report a problem*. Before it is written: under *On demand*, "Tap Write
 * insight…" and *Write insight* with the **AI** mark; under an automatic mode, written at once
 * ([ItemContext.writesAtOnce], [tapped]), *Writing…* with the turning record meanwhile.
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
    val shown by remember(track, item, context.notes) { context.notes.item(track, item) }
        .collectAsState(ItemShown.Gathering)
    val atOnce = context.writesAtOnce(tapped)
    val offered = ItemInsights.offered(track)
    LaunchedEffect(track.normalized.lookupKey, track.evidence?.fingerprint, item, atOnce, offered) {
        if (atOnce && offered) context.notes.write(track, item)
    }
    val unavailable = context.unavailable
    val state = (shown as? ItemShown.Shown)?.state
    if (unavailable != null && state !is InsightState.Written && state !is InsightState.Writing) {
        ModelUnavailable(unavailable, context.onModelSettings)
        return
    }
    when (val now = shown) {
        ItemShown.Gathering -> Gathering()
        ItemShown.Off -> Quiet(stringResource(R.string.home_insight_off))
        ItemShown.NoService -> Quiet(stringResource(R.string.home_insight_unavailable))
        ItemShown.Offered ->
            if (atOnce) {
                Writing()
            } else {
                Quiet(stringResource(R.string.home_item_tap_write))
                WriteButton(item) { context.notes.write(track, item) }
            }
        is ItemShown.Shown -> Shown(item, now, track, context)
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
            Writing()
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
            if (!state.complete) Quiet(stringResource(R.string.home_insight_incomplete))
            ReportAction { context.onReport(own, state, item) }
        }

        InsightState.NoEvidence -> Quiet(stringResource(noEvidence(item)))

        is InsightState.Failed ->
            RetryLine(
                stringResource(R.string.home_item_failed, failureText(state.reason)),
                source = "",
                onRetry = { context.notes.write(track, item) },
                modifier = Modifier.testTag(ITEM_FAILED_TAG),
                action = stringResource(R.string.home_ai_retry_action),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                outlined = true,
            )

        is InsightState.Idle, InsightState.Stopped -> Unit
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

// *Write insight*, with the **AI** mark: a paid call.
@Composable
private fun WriteButton(
    item: InsightItem,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.testTag(itemWriteTag(item))) {
        AiLabel(stringResource(R.string.home_write))
    }
}

@Composable
private fun Gathering() =
    Busy(
        stringResource(R.string.home_insight_gathering),
        MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
private fun Writing() =
    Busy(
        stringResource(R.string.home_insight_writing),
        MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textModifier = Modifier.testTag(ITEM_WRITING_TAG),
    )

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun noEvidence(item: InsightItem): Int =
    when (item) {
        InsightItem.SONG -> R.string.home_item_no_evidence_song
        InsightItem.ARTIST -> R.string.home_item_no_evidence_artist
        InsightItem.ALBUM -> R.string.home_item_no_evidence_album
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
    view = shown.view,
    settings = settings,
    notes = actions.itemNotes,
    cite = cite,
    onReport = onReport,
    readAloud = actions.readAloud,
    unavailable = state.unavailable.takeIf { settings.writeInsights },
    onModelSettings = {
        state.unavailable?.let { actions.openSettingsAt(SettingsTarget.model(it.provider)) } ?: actions.openSettings()
    },
)
