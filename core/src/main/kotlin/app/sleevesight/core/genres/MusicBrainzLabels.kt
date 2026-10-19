package app.sleevesight.core.genres

import app.sleevesight.core.resolution.Lookup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * An artist's MusicBrainz tags (`inc=tags` on the artist), most voted first: `MusicBrainzClient`
 * in `:sources`, a fake in tests. Kept apart from [app.sleevesight.core.resolution.MusicBrainzLookup],
 * which asks for core data only: tags are CC BY-NC-SA 3.0, used for genre labels only and never
 * sent in a prompt (`design/architecture.md`, *MusicBrainz tags and genres (#9)*, *Conditions*).
 */
fun interface ArtistTags {
    suspend fun artistTags(mbid: String): Lookup<List<String>>
}

/**
 * The genre labels from the MusicBrainz tags of a track's artists (D12, revised; #84): each
 * artist's tags, in credit order, through the [vocabulary], at most [GenreVocabulary.MAX_LABELS],
 * each genre once; each label links its artist's MusicBrainz page. The pipeline asks for them after
 * the evidence is assembled, for the artists MusicBrainz resolved (`SourceResult.labelArtists`), so
 * the insight never waits for them.
 *
 * Tags are artist-level, so an artist's are kept by MusicBrainz id, for [lifetime], at most
 * [capacity] artists, the least recently used dropped first: a second track by the same artist
 * asks nothing. They are kept in memory, for the process: a table of their own in `:store` would
 * need its own schema, size accounting, eviction and lifetime, for what is one request per artist
 * off the evidence path. A failed request is not kept, so the next track by the artist asks again,
 * and gives that artist no labels meanwhile; nothing else follows from it, since the labels are no
 * evidence. An artist MusicBrainz does not know is kept as having no tags.
 */
class MusicBrainzLabels(
    private val tags: ArtistTags,
    private val vocabulary: GenreVocabulary,
    private val clock: Clock = Clock.System,
    private val lifetime: Duration = DEFAULT_LIFETIME,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Kept(
        val tags: List<String>,
        val at: Instant,
    )

    private val mutex = Mutex()

    // Insertion-ordered; an entry is moved to the end when used, so the first is the least recent.
    private val kept = LinkedHashMap<String, Kept>()

    /** The labels from the tags of [artists], MusicBrainz ids in credit order; none for one whose request failed. */
    suspend fun labels(artists: List<String>): List<GenreLabel> =
        artists
            .distinct()
            .flatMap { mbid -> vocabulary.labelsFor(tagsOf(mbid).orEmpty(), artistPage(mbid)) }
            .distinctBy { it.identity }
            .take(GenreVocabulary.MAX_LABELS)

    // The artist's tags, kept or asked for; null when the request failed.
    private suspend fun tagsOf(mbid: String): List<String>? =
        cached(mbid) ?: when (val answer = tags.artistTags(mbid)) {
            is Lookup.Found -> answer.value.also { keep(mbid, it) }
            Lookup.NotFound -> emptyList<String>().also { keep(mbid, it) }
            is Lookup.Failed -> null
        }

    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    private suspend fun cached(mbid: String): List<String>? =
        mutex.withLock {
            val entry = kept.remove(mbid)
            if (entry == null || clock.now() - entry.at >= lifetime) {
                null
            } else {
                kept[mbid] = entry
                entry.tags
            }
        }

    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
    private suspend fun keep(
        mbid: String,
        found: List<String>,
    ) = mutex.withLock {
        kept.remove(mbid)
        kept[mbid] = Kept(found, clock.now())
        while (kept.size > capacity) kept.remove(kept.keys.first())
    }

    companion object {
        /** As long as MusicBrainz's retrieval lifetime (D9): tags change no faster than its other data. */
        val DEFAULT_LIFETIME: Duration = 30.days

        /** A few hundred bytes an artist, so a few hundred artists cost the process little. */
        const val DEFAULT_CAPACITY: Int = 500

        /** The artist's page on MusicBrainz, which a label from its tags links. */
        fun artistPage(mbid: String): String = "https://musicbrainz.org/artist/$mbid"
    }
}
