package app.sleevesight.core.genres

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Tags a source gave, each a `T` ([CountedTag] for the labels' tags, with their counts, #1019),
 * kept by key so they are not asked again: an artist's MusicBrainz tags
 * ([MusicBrainzLabels]), an album's Last.fm tags ([LastFmLabels]). In [artists], the artist cache
 * (#238), under [part], for its sources' lifetime (D9), so they are not asked again after a restart
 * either; with *Keep what sources said about each artist* off (#420), that cache keeps and reads
 * nothing, and nothing is kept in memory in its place, since it still persists. With no artist
 * cache (one that does not persist, [ArtistResults.persists]: the CLI without a cache, tests), in
 * memory instead, for [lifetime], at most [capacity] keys, the least recently used dropped first; never
 * both, so tags are not kept in memory past the cache's lifetime. A failed request is not kept, so
 * the next track asks again; a key the source does not know is kept as having no tags.
 */
internal class KeptTags<T>(
    private val part: ArtistPart,
    tag: KSerializer<T>,
    private val artists: ArtistResults,
    private val clock: Clock,
    private val lifetime: Duration,
    private val capacity: Int,
) {
    private class Kept<T>(
        val tags: List<T>,
        val at: Instant,
    )

    // A JSON array of the tags as the source gave them, each a [tag].
    private val tags = ListSerializer(tag)

    private val mutex = Mutex()

    // Insertion-ordered; an entry is moved to the end when used, so the first is the least recent.
    private val kept = LinkedHashMap<String, Kept<T>>()

    /** The tags for [key], kept or asked for with [ask]; null when the request failed. */
    suspend fun tagsOf(
        key: String,
        ask: suspend () -> Lookup<List<T>>,
    ): List<T>? = (if (artists.persists) stored(key) else cached(key)) ?: asked(key, ask)

    // The tags the source gives, kept; null when the request failed.
    private suspend fun asked(
        key: String,
        ask: suspend () -> Lookup<List<T>>,
    ): List<T>? =
        when (val answer = ask()) {
            is Lookup.Found -> answer.value.also { keepTags(key, it) }
            Lookup.NotFound -> emptyList<T>().also { keepTags(key, it) }
            is Lookup.Failed -> null
        }

    // What the artist cache keeps for [key]; null for none, or a value that cannot be read.
    private suspend fun stored(key: String): List<T>? {
        val found = artists.get(part, key)
        return try {
            found?.let { json.decodeFromString(tags, it.value) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // In the artist cache, or in memory without one.
    private suspend fun keepTags(
        key: String,
        found: List<T>,
    ) {
        if (artists.persists) {
            artists.keep(part, key, json.encodeToString(tags, found))
        } else {
            keep(key, found)
        }
    }

    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    private suspend fun cached(key: String): List<T>? =
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
        found: List<T>,
    ) = mutex.withLock {
        kept.remove(key)
        kept[key] = Kept(found, clock.now())
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }

    private companion object {
        val json = Json
    }
}
