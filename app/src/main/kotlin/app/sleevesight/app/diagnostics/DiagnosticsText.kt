package app.sleevesight.app.diagnostics

import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.store.CacheStats
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The diagnostics as text, for the screen and the export: plain lines in English, like the CLI's
 * report, whose event lines they share ([EventLine]). Times are in milliseconds, as measured.
 */
internal object DiagnosticsText {
    /** A track change on one line: the player, the tracks it left and entered, and its features. */
    fun change(change: TrackChangeRecord): String =
        "${change.player}: ${change.from?.let(::track) ?: "(none)"} -> ${track(change.to)}; " +
            "time left ${ms(change.timeLeft)}, since last action ${ms(change.sinceLastAction)}, " +
            "learned crossfade ${ms(change.learnedCrossfade)}, ${change.states.size} states"

    /** A playback state before a change, timed from the change. */
    fun state(state: PlaybackStateRecord): String =
        "-${ms(state.receivedBefore)} ${state.status}, position ${ms(state.position)}" +
            state.positionBefore?.let { " (taken ${ms(it)} before)" }.orEmpty() +
            ", speed ${state.speed}"

    /** One cache's size. */
    fun cache(
        name: String,
        stats: TableStats,
    ): String = "$name: ${stats.entries} entries, ${stats.bytes} bytes"

    /** The crossfade learned for [player]. */
    fun learned(
        player: String,
        crossfade: Duration,
    ): String = "$player: learned crossfade ${ms(crossfade)}"

    /**
     * The whole export, to [out]: the header, what skip detection has learned ([learned]; null when
     * nothing supplies it), the caches' sizes ([caches]; null when they could not be read), the
     * events kept, oldest first, and the track changes recorded, newest first, a page at a time from
     * [changes]. Returns whether all of it was read: false when the caches or a page of the history
     * could not be, which the text says where it happened.
     */
    @Suppress("LongParameterList") // Each part of the export, in order.
    suspend fun export(
        out: Appendable,
        version: String,
        exportedAt: Instant,
        learned: Map<String, Duration>?,
        caches: CacheStats?,
        events: List<LoggedEvent>,
        changes: suspend (before: Long?) -> PageResult,
    ): Boolean {
        out.appendLine("Sleevesight diagnostics, version $version, exported $exportedAt")
        out.appendLine()
        out.appendLine("## Skip detection")
        when {
            learned == null -> out.appendLine(NOT_LEARNING)
            learned.isEmpty() -> out.appendLine(NOTHING_LEARNED)
            else -> learned.toSortedMap().forEach { (player, crossfade) -> out.appendLine(learned(player, crossfade)) }
        }
        out.appendLine()
        out.appendLine("## Caches")
        if (caches == null) {
            out.appendLine("The caches could not be read.")
        } else {
            out.appendLine(cache("retrieval", caches.retrieval))
            out.appendLine(cache("artists", caches.artists))
            out.appendLine(cache("artist results", caches.artistResults))
            out.appendLine(cache("synthesis", caches.synthesis))
        }
        out.appendLine()
        out.appendLine("## Pipeline events, oldest first (${events.size})")
        events.forEach { out.appendLine("${it.at} ${EventLine.of(it.event)}") }
        out.appendLine()
        out.appendLine("## Track changes, newest first")
        var before: Long? = null
        do {
            val page = changes(before)
            if (page !is PageResult.Read) {
                out.appendLine("The history could not be read.")
                return false
            }
            for (change in page.page.changes) {
                out.appendLine("${change.changedAt} ${change(change)}")
                change.states.forEach { out.appendLine("  ${state(it)}") }
            }
            before = page.page.next
        } while (before != null)
        return caches != null
    }

    /** Said when nothing supplies what skip detection has learned. */
    const val NOT_LEARNING = "Skip detection learns nothing in this version."

    /** Said when skip detection has learned nothing yet. */
    const val NOTHING_LEARNED = "Nothing learned yet."

    private fun track(ref: TrackRef): String = "\"${ref.title}\"" + ref.mediaId?.let { " ($it)" }.orEmpty()

    private fun ms(duration: Duration?): String = duration?.let { "${it.inWholeMilliseconds} ms" } ?: "-"
}
