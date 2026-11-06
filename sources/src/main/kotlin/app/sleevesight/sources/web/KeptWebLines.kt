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
 * `SongWebSource`), kept under [part] by its key in [kept], "found nothing" included; with a [kept]
 * that keeps nothing ([ArtistResults.persists] false: the CLI, tests), in memory for the most recent
 * [REMEMBERED]. One search at a time per key: a second while one is under way takes its outcome.
 */
internal class KeptWebLines(
    private val part: ArtistPart,
    private val kept: ArtistResults,
) {
    // The web's lines by key, while [kept] keeps nothing.
    private val remembered = Lru<String, List<EvidenceDraft>>(REMEMBERED)

    // The searches under way, by key.
    private val searching = OneSearchAtATime<List<EvidenceDraft>>()

    /** The lines kept for [key], none included; null when none is kept. */
    suspend fun kept(key: String): List<EvidenceDraft>? =
        if (kept.persists) kept.get(part, key)?.let { KeptDrafts.decode(it.value) } else remembered.get(key)

    /** [lines] kept for [key], none included. */
    suspend fun keep(
        key: String,
        lines: List<EvidenceDraft>,
    ) {
        if (kept.persists) kept.keep(part, key, KeptDrafts.encode(lines)) else remembered.put(key, lines)
    }

    /** What is kept for [key] kept again, from now; nothing when none is. */
    suspend fun renew(key: String) {
        if (kept.persists) {
            kept.get(part, key)?.let { kept.keep(part, key, it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

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
    }
}
