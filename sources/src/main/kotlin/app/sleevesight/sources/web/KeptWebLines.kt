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
    private val marked: Boolean = false,
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
    ) = keep(key, lines, searched = true)

    /**
     * [lines] kept for [key], none included, and, when [marked] (the artist's), whether a search found
     * them ([searched], #1308), in a mark beside them ([unsearched]), written with them at every keep, so
     * lines kept again replace the mark of those they replace.
     */
    suspend fun keep(
        key: String,
        lines: List<EvidenceDraft>,
        searched: Boolean,
    ) {
        if (kept.persists) {
            kept.keep(part, key, KeptDrafts.encode(lines))
            if (marked) kept.keep(part, markOf(key), if (searched) SEARCHED else UNSEARCHED)
        } else {
            remembered.put(key, lines)
            if (marked) unsearchedHere.put(key, !searched)
        }
    }

    /** Whether the lines kept for [key] were kept with no search made ([keep] with `searched` false). */
    suspend fun unsearched(key: String): Boolean =
        if (kept.persists) {
            kept.get(part, key) != null && kept.get(part, markOf(key))?.value == UNSEARCHED
        } else {
            remembered.get(key) != null && unsearchedHere.get(key) == true
        }

    /**
     * What is kept for [key] kept again, from now, with its mark; nothing when none is: a mark whose
     * lines are gone is not kept again.
     */
    suspend fun renew(key: String) {
        if (kept.persists) {
            val lines = kept.getCarried(part, before, key) ?: return
            kept.keep(part, key, lines.value)
            if (marked) kept.get(part, markOf(key))?.let { kept.keep(part, markOf(key), it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

    // The key of [key]'s mark of whether its lines were found by a search.
    private fun markOf(key: String): String = "$key$MARK_SUFFIX"

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
        private const val MARK_SUFFIX = "#search"
        private const val UNSEARCHED = "unsearched"
        private const val SEARCHED = "searched"
    }
}
