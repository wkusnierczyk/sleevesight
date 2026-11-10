package app.sleevesight.app.pipeline

import app.sleevesight.core.evidence.EvidenceDraft

// Steps of the coordinator's ledger and runs, apart from its class (`PipelineCoordinator`).

/**
 * [this] after a run's report: its [card], [wouldWrite], [paused] and [foundNew] as the coordinator
 * decided them, and the lines beyond the track's own that [card]'s run took (#1131), kept as they were
 * while it has none (expired).
 */
internal fun CoordinatorState.Ledger.recording(
    card: Card?,
    wouldWrite: CoordinatorState.WouldWrite?,
    paused: WriteSettings?,
    foundNew: String?,
): CoordinatorState.Ledger =
    copy(card = card, wouldWrite = wouldWrite, paused = paused, foundNew = foundNew, items = card?.items ?: items)

/**
 * [this] replaced by run [id] of its key and report that asks again (#192), [retried] for a tap on
 * *Retry*, taking [items] (#1131). A tap shown for the run replaced, or one before it, which offered
 * Write, may still be on its way, or be kept already: it is carried over. Once retried, a run stays so
 * through back online's replacements (#724).
 */
internal fun CoordinatorState.Run.askingAgain(
    id: RunId,
    retried: Boolean,
    items: List<EvidenceDraft>,
): CoordinatorState.Run =
    CoordinatorState.Run(
        id,
        key,
        nowPlaying,
        again = true,
        keepsTap = offered || keepsTap,
        tapped = keepsTap && tapped,
        retried = retried || this.retried,
        items = items,
    )
