package app.sleevesight.core.pipeline

import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SelfRevision
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When a reply still streaming is read again for a sign of revision (`SelfRevision.ofPartial`, #798)
 * and shown (#1351). Each reading costs time growing with the text so far, so reading it at every
 * piece cost time growing with the square of the reply, more again on the phone. Asked only once the
 * reply can no longer be `NO_EVIDENCE` (`GroundingCheck.mayBeNoEvidence`), a piece is read: the first;
 * one that ends a line, [LINE] after the last reading ended; any, [EVERY] after it. Never sooner than
 * [SHARE] times as long as the last reading took after it, so on a long reply the readings take no
 * more than about a fifth of the time it streams. A piece not read is not shown either: the card
 * shows only text read for a sign, so the draft never shows past one; the text held back shows with
 * the next piece read, or as the reply ends.
 */
internal class PartialPace(
    private val timeSource: TimeSource,
) {
    // When the last reading ended, and how long it took.
    private var last: TimeMark? = null
    private var took: Duration = Duration.ZERO

    // Whether the reply revised itself: what was shown stays until the check decides.
    private var frozen = false

    /**
     * [show]s [partial], the reply so far, [piece] just added to it, when it is read now: not while it
     * may still be `NO_EVIDENCE`, or is, as the check reads it, since the marker is no prose for the
     * card; nor once it revises itself, as the card would show it (#798).
     */
    fun next(
        partial: String,
        piece: String,
        show: (String) -> Unit,
    ) {
        if (frozen || GroundingCheck.mayBeNoEvidence(partial)) return
        read(piece) {
            frozen = SelfRevision.ofPartial(partial).freezes()
            if (!frozen) show(partial)
        }
    }

    /**
     * Reads the reply ([reading]), [piece] just added to it, when it is due; whether it was. The
     * time is [timeSource]'s, so a test's virtual time sets the pace.
     */
    fun read(
        piece: String,
        reading: () -> Unit,
    ): Boolean {
        val since = last?.elapsedNow()
        val wait = if ('\n' in piece) LINE else EVERY
        val due = since == null || since >= maxOf(wait, took * SHARE)
        if (due) {
            val start = timeSource.markNow()
            reading()
            took = start.elapsedNow()
            last = timeSource.markNow()
        }
        return due
    }

    companion object {
        /** The longest a piece waits to be read, while readings take little: the card lags no more. */
        val EVERY: Duration = 200.milliseconds

        /** The shortest wait for a piece that ends a line. */
        val LINE: Duration = 50.milliseconds

        /** How many times as long as the last reading took the next waits at least. */
        const val SHARE: Int = 4
    }
}
