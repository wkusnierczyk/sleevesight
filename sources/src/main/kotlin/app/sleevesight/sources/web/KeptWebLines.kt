package app.sleevesight.sources.web

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.sources.KeptDrafts
import app.sleevesight.sources.OneSearchAtATime
import app.sleevesight.sources.lastfm.Lru

/**
 * Web lines found by a paid search for one thing, an artist (#912, `ArtistWebSource`) or a song (#958,
 * `SongWebSource`), kept under [part] by its key in [kept], "found nothing" included, or carried over
 * from [before], the part before Exa's references were decoded ([getCarried], #1165); with a [kept]
 * that keeps nothing ([ArtistResults.persists] false: the CLI, tests), in memory for the most recent
 * [REMEMBERED]. One search at a time per key: a second while one is under way takes its outcome.
 */
internal class KeptWebLines(
    private val part: ArtistPart,
    private val kept: ArtistResults,
    private val before: ArtistPart,
) {
    // The web's lines by key, while [kept] keeps nothing.
    private val remembered = Lru<String, List<EvidenceDraft>>(REMEMBERED)

    // Whether those lines were kept with no search made, by key, while [kept] keeps nothing (#1308).
    private val unsearchedHere = Lru<String, Boolean>(REMEMBERED)

    // The searches under way, by key.
    private val searching = OneSearchAtATime<List<EvidenceDraft>>()

    /** The lines kept for [key], none included; null when none is kept. */
    suspend fun kept(key: String): List<EvidenceDraft>? =
        if (kept.persists) {
            kept.getCarried(part, before, key)?.let { KeptDrafts.decode(it.value) }
        } else {
            remembered.get(key)
        }

    /** [lines] kept for [key], none included, found by a search. */
    suspend fun keep(
        key: String,
        lines: List<EvidenceDraft>,
    ) {
        if (kept.persists) {
            kept.keep(part, key, KeptDrafts.encode(lines))
        } else {
            remembered.put(key, lines)
            unsearchedHere.put(key, false)
        }
    }

    /**
     * [lines] kept for [key], none included, and whether a search found them ([searched], #1308), in a
     * mark beside them ([unsearched]), kept after them: it stands while it is as fresh as they are, so
     * lines kept again later by the app's own search leave it behind.
     */
    suspend fun keep(
        key: String,
        lines: List<EvidenceDraft>,
        searched: Boolean,
    ) {
        keep(key, lines)
        if (kept.persists) {
            kept.keep(part, markOf(key), if (searched) SEARCHED else UNSEARCHED)
        } else {
            unsearchedHere.put(key, !searched)
        }
    }

    /** Whether the lines kept for [key] were kept with no search made ([keep] with `searched` false). */
    suspend fun unsearched(key: String): Boolean =
        if (kept.persists) {
            val lines = kept.get(part, key)
            val mark = kept.get(part, markOf(key))
            lines != null && mark?.value == UNSEARCHED && mark.freshUntil >= lines.freshUntil
        } else {
            unsearchedHere.get(key) == true
        }

    /** What is kept for [key] kept again, from now, its mark after it; nothing when none is. */
    suspend fun renew(key: String) {
        if (kept.persists) {
            kept.getCarried(part, before, key)?.let { kept.keep(part, key, it.value) }
            kept.get(part, markOf(key))?.let { kept.keep(part, markOf(key), it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

    // The key of [key]'s mark of lines kept with no search made.
    private fun markOf(key: String): String = "$key$UNSEARCHED_SUFFIX"

    /**
     * The lines kept for [key], else what [engine] finds, one at a time per key, kept, none included;
     * null when it failed, and nothing is kept, or when [mayPay], asked after the waits, right before
     * the request, says no, and nothing is sent. [keeping] is given what was found before it is kept.
     */
    suspend fun search(
        key: String,
        keeping: (List<EvidenceDraft>) -> Unit,
        mayPay: () -> Boolean,
        engine: suspend () -> Lookup<List<EvidenceDraft>>,
    ): List<EvidenceDraft>? =
        try {
            searching.once(key) { kept(key) ?: searched(key, keeping, mayPay, engine) }
        } catch (
            @Suppress("SwallowedException") e: NotPaid,
        ) {
            // Its gate closed after the waits: nothing sent, nothing kept; a write waiting for this one
            // searches, or reads, itself.
            null
        }

    // What [engine] finds, none included, kept; null when it failed. Closed, [NotPaid].
    private suspend fun searched(
        key: String,
        keeping: (List<EvidenceDraft>) -> Unit,
        mayPay: () -> Boolean,
        engine: suspend () -> Lookup<List<EvidenceDraft>>,
    ): List<EvidenceDraft>? {
        if (!mayPay()) throw NotPaid()
        val found =
            when (val answer = engine()) {
                is Lookup.Found -> answer.value
                Lookup.NotFound -> emptyList()
                is Lookup.Failed -> null
            }
        found?.let {
            keeping(it)
            keep(key, it)
        }
        return found
    }

    // A search its caller's gate refused right before the request (#912's review).
    private class NotPaid : Exception("not paid")

    companion object {
        /** The most recent keys whose lines are kept in memory, without a [kept] that keeps them. */
        const val REMEMBERED: Int = 256

        // A mark's key after its lines' (#1308), and its value.
        private const val UNSEARCHED_SUFFIX = "#unsearched"
        private const val UNSEARCHED = "unsearched"
        private const val SEARCHED = "searched"
    }
}
