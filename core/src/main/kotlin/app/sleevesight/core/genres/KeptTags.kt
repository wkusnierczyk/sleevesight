package app.sleevesight.core.genres

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Tags a source gave, kept by key so they are not asked again: an artist's MusicBrainz tags
 * ([MusicBrainzLabels]), an album's Last.fm tags ([LastFmLabels]). In [artists], the artist cache
 * (#238), under [part], for its sources' lifetime (D9), so they are not asked again after a restart
 * either; with *Keep what sources said about each artist* off (#420), that cache keeps and reads
 * nothing, and nothing is kept in memory in its place, since it still persists. With no artist
 * cache (one that does not persist, [ArtistResults.persists]: the CLI without a cache, tests), in
 * memory instead, for [lifetime], at most [capacity] keys, the least recently used dropped first; never
 * both, so tags are not kept in memory past the cache's lifetime. A failed request is not kept, so
 * the next track asks again; a key the source does not know is kept as having no tags.
 */
internal class KeptTags(
    private val part: ArtistPart,
    private val artists: ArtistResults,
    private val clock: Clock,
    private val lifetime: Duration,
    private val capacity: Int,
) {
    private class Kept(
        val tags: List<String>,
        val at: Instant,
    )

    private val mutex = Mutex()

    // Insertion-ordered; an entry is moved to the end when used, so the first is the least recent.
    private val kept = LinkedHashMap<String, Kept>()

    /** The tags for [key], kept or asked for with [ask]; null when the request failed. */
    suspend fun tagsOf(
        key: String,
        ask: suspend () -> Lookup<List<String>>,
    ): List<String>? = (if (artists.persists) stored(key) else cached(key)) ?: asked(key, ask)

    // The tags the source gives, kept; null when the request failed.
    private suspend fun asked(
        key: String,
        ask: suspend () -> Lookup<List<String>>,
    ): List<String>? =
        when (val answer = ask()) {
            is Lookup.Found -> answer.value.also { keepTags(key, it) }
            Lookup.NotFound -> emptyList<String>().also { keepTags(key, it) }
            is Lookup.Failed -> null
        }

    // What the artist cache keeps for [key]; null for none, or a value that cannot be read.
    private suspend fun stored(key: String): List<String>? {
        val found = artists.get(part, key)
        return try {
            found?.let { json.decodeFromString(TAGS, it.value) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // In the artist cache, or in memory without one.
    private suspend fun keepTags(
        key: String,
        found: List<String>,
    ) {
        if (artists.persists) {
            artists.keep(part, key, json.encodeToString(TAGS, found))
        } else {
            keep(key, found)
        }
    }

    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    private suspend fun cached(key: String): List<String>? =
        mutex.withLock {
            val entry = kept.remove(key)
            if (entry == null || clock.now() - entry.at >= lifetime) {
                null
            } else {
                kept[key] = entry
                entry.tags
            }
        }

    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    private suspend fun keep(
        key: String,
        found: List<String>,
    ) = mutex.withLock {
        kept.remove(key)
        kept[key] = Kept(found, clock.now())
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }

    private companion object {
        val json = Json

        /** A JSON array of the tags, as the source gave them; they go through the vocabulary when read. */
        val TAGS = ListSerializer(String.serializer())
    }
}
