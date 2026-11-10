package app.sleevesight.sources

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Evidence lines as the artist-level table keeps them (#522, #885, #912): an album's lines
 * (`AlbumSource`), the web's lines for an album or an artist (`ArtistWebSource`), and the lines an
 * artist's or an album's search in the track's language found (`LanguageLinesSource`, #939), each its
 * source, scope, text, address and time, and, only when set, what it is about and its language: a
 * line with neither is encoded as before they were kept. The encoding is the parts' encoding 1:
 * changing it takes a new `ArtistPart.encoding`.
 */
internal object KeptDrafts {
    private val json = Json { encodeDefaults = false }

    @Serializable
    @Suppress("LongParameterList") // One for each of the line's fields kept.
    private class KeptLine(
        val s: String,
        val c: String,
        val t: String,
        val u: String? = null,
        val a: Long,
        val b: String? = null,
        val l: String? = null,
    )

    /** [drafts], encoded. */
    fun encode(drafts: List<EvidenceDraft>): String =
        json.encodeToString(
            drafts.map {
                KeptLine(
                    it.source.name,
                    it.scope.key,
                    it.text,
                    it.url,
                    it.retrievedAt.toEpochMilliseconds(),
                    it.about,
                    it.language,
                )
            },
        )

    /** The lines kept in [value], or null when they cannot be read (the table's entry from another encoding). */
    fun decode(value: String): List<EvidenceDraft>? =
        try {
            json.decodeFromString<List<KeptLine>>(value).map { line ->
                EvidenceDraft(
                    SourceId.valueOf(line.s),
                    Scope.entries.first { it.key == line.c },
                    line.t,
                    line.u,
                    Instant.fromEpochMilliseconds(line.a),
                    about = line.b,
                    language = line.l,
                )
            }
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") e: NoSuchElementException,
        ) {
            null
        }
}

/**
 * One paid search at a time per key (#885: an album, #912: an artist), or an album's free
 * lookups (#1187): a second asked for the same key while one is under way waits for it and takes
 * its outcome (what it kept), rather than paying, or asking the sources, for its own. When the one
 * under way is cancelled (a Stop), or fails by throwing, the next takes its place and reads, or
 * searches, itself.
 */
internal class OneSearchAtATime<T> {
    // A search's outcome, shared with those that waited for it: its [value], null when it failed.
    private class Searched<T>(
        val value: T?,
    )

    private val searching = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Searched<T>?>>()

    /** [search] for [key], one at a time: its outcome, or the one under way's. */
    suspend fun once(
        key: String,
        search: suspend () -> T?,
    ): T? {
        var outcome: Searched<T>? = null
        while (outcome == null) {
            val mine = CompletableDeferred<Searched<T>?>()
            val pending =
                searching.withLock {
                    inFlight[key]?.takeIf { !it.isCompleted }
                        ?: mine.also { inFlight[key] = it }
                }
            outcome = if (pending === mine) searchedOnce(key, mine, search) else pending.await()
        }
        return outcome.value
    }

    // [search] made for [key], its outcome told to [pending] whatever happens (null when cancelled).
    private suspend fun searchedOnce(
        key: String,
        pending: CompletableDeferred<Searched<T>?>,
        search: suspend () -> T?,
    ): Searched<T> =
        try {
            Searched(search()).also {
                pending.complete(it)
                searching.withLock { if (inFlight[key] === pending) inFlight.remove(key) }
            }
        } finally {
            // Cancelled or failed: those waiting go on, and the next takes its place.
            pending.complete(null)
        }
}
