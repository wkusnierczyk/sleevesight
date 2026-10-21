package app.sleevesight.sources.lastfm

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How Last.fm's answer for one credited artist was judged (`design/architecture.md`, *Source
 * adapters*, *Last.fm verification*).
 */
sealed interface LastFmMatch {
    /**
     * Last.fm's artist has the MusicBrainz id resolution gave the credited artist, and no other
     * artist shares its page: its lines may be cited.
     */
    data object Verified : LastFmMatch

    /**
     * Last.fm's artist has the resolved MusicBrainz id and its bio does not say the name is shared,
     * but MusicBrainz does not show the name is the artist's alone: another artist has it, or the
     * search could not tell. The bio's sentences may be cited; the listeners, tags and similar
     * artists, which every artist of the name adds to, are shown, never cited.
     */
    data object BioVerified : LastFmMatch

    /**
     * Same name, and nothing to check it against, or a page its bio says several artists share, or
     * one a failed search left unchecked: its lines are shown, never cited (D4).
     */
    data object Unverified : LastFmMatch

    /** Last.fm answered with another artist: a different MusicBrainz id, or a corrected name nothing confirms. */
    data class Rejected(
        val reason: String,
    ) : LastFmMatch

    /** Last.fm does not know the name. */
    data object NotFound : LastFmMatch

    /** No usable answer: [reason] is the network error, or Last.fm's error code. */
    data class Failed(
        val reason: String,
    ) : LastFmMatch

    /** Whether the artist so judged may be shown: verified, its bio verified, or unverified. */
    val shows: Boolean
        get() = this == Verified || this == BioVerified || this == Unverified
}

/**
 * One credited artist's Last.fm lookup: the name as credited, the judgement, Last.fm's answer, and
 * the genre labels from its tags (none unless verified or unverified).
 *
 * @property artist the artist Last.fm answered with, whatever the judgement. For a
 *   [LastFmMatch.Rejected] match it is another artist, kept for the reason only: show nothing of it.
 *   [shown] is the artist a card may show.
 * @property provisional a failed MusicBrainz search left the match unverified, where a later lookup
 *   may verify it.
 */
data class LastFmArtistLookup(
    val credited: String,
    val match: LastFmMatch,
    val artist: LastFmArtist?,
    val genres: List<GenreLabel>,
    val provisional: Boolean = false,
) {
    /**
     * [artist] when the match is verified, bio-verified or unverified ([LastFmMatch.shows]), the
     * only artist whose data may be shown.
     */
    val shown: LastFmArtist?
        get() = artist.takeIf { match.shows }
}

/**
 * A [match], and whether a failed request left it short of a final judgement, so that a later
 * lookup may judge it otherwise ([app.sleevesight.core.sources.SourceResult.provisional]).
 */
internal data class Judgement(
    val match: LastFmMatch,
    val provisional: Boolean = false,
)

/**
 * The check of Last.fm's [artist] against the credited name and the resolved artist, in this order:
 *
 * 1. Both have a MusicBrainz id: a different one rejects the match. With the same id, even under a
 *    corrected name: Last.fm keeps one page per name, so every artist of that name shares it.
 *    - A bio that says the name is shared ([LastFmArtist.sharedPage]) leaves the match unverified.
 *    - Otherwise MusicBrainz's artist search ([ownership]) decides: the name the artist's alone
 *      verifies the match; another artist of the name, or a search that cannot tell, verifies the
 *      bio only ([LastFmMatch.BioVerified]), since the listeners, tags and similar artists mix every
 *      artist of the name; a search that fails leaves the match unverified, and provisional.
 * 2. Otherwise a name Last.fm corrected (it differs beyond case, accents, spacing and punctuation)
 *    is rejected: autocorrection is how a small act's name lands on an unrelated band's page.
 * 3. Otherwise the match is unverified.
 *
 * Name matching folds case and accents and keeps letters and digits only. One check serves every
 * track its adapter looks up, and keeps the search's answers, all but failures, by the name as
 * sent and the MusicBrainz id: in memory for the most recent [VERDICTS], and in [artists], the
 * artist cache ([OWNERSHIP_PART], #114, #238), for MusicBrainz's lifetime (D9), so a restart, or a
 * new adapter, does not search again.
 */
internal class ArtistCheck(
    private val accentFolder: AccentFolder,
    private val musicBrainz: MusicBrainzLookup,
    private val artists: ArtistResults = ArtistResults.None,
) {
    private val verdicts = Lru<Pair<String, String>, Ownership>(VERDICTS)

    suspend fun judge(
        credited: String,
        artist: LastFmArtist,
        resolved: ResolvedArtist?,
    ): Judgement {
        val resolvedMbid = resolved?.mbid?.takeIf { it.isNotBlank() }
        val lastFmMbid = artist.mbid
        return when {
            resolvedMbid != null && lastFmMbid != null -> {
                when {
                    !resolvedMbid.equals(lastFmMbid, ignoreCase = true) -> {
                        Judgement(
                            LastFmMatch.Rejected(
                                "Last.fm's ${artist.name} is a different MusicBrainz artist from $credited",
                            ),
                        )
                    }

                    // The bio is read first: it costs no request.
                    artist.sharedPage -> {
                        Judgement(LastFmMatch.Unverified)
                    }

                    else -> {
                        when (ownership(resolvedMbid, artist.name)) {
                            Ownership.OWNED -> Judgement(LastFmMatch.Verified)
                            Ownership.FAILED -> Judgement(LastFmMatch.Unverified, provisional = true)
                            Ownership.SHARED, Ownership.UNKNOWN -> Judgement(LastFmMatch.BioVerified)
                        }
                    }
                }
            }

            key(artist.name) != key(credited) -> {
                Judgement(
                    LastFmMatch.Rejected(
                        "Last.fm corrected $credited to ${artist.name}, and no MusicBrainz id confirms it",
                    ),
                )
            }

            else -> {
                Judgement(LastFmMatch.Unverified)
            }
        }
    }

    /** What MusicBrainz's artist search says of a page's name. */
    private enum class Ownership {
        /** The search is complete, and the artist is the only one of the name. */
        OWNED,

        /** Another artist has the name. */
        SHARED,

        /** The search answered, but proves neither: its page holds only part of the matches, or not the artist. */
        UNKNOWN,

        /**
         * A request failed, or answered with a count below the artists it listed; a later search may
         * answer. Never kept.
         */
        FAILED,
    }

    /**
     * Whether [name] is the artist [mbid]'s alone, by name key, from one page of MusicBrainz's
     * artist search: another artist of the name on it shares the name; the artist owns it only when
     * the page holds every match the search counted ([MbArtistSearch.total] equal to the artists on
     * the page) and the artist under the name, alone. The count is the search index's, not a promise
     * that no other artist has the name, so the verdict is no stronger than the search; a count
     * below the artists listed is wrong, and fails the search, as `MusicBrainzClient.searchArtists`
     * already fails such an answer (a backstop here). Anything else proves neither, as does a
     * name with no letters or digits, which every name key would match. The answer is kept by the
     * query as sent and the id, in memory and in the artist cache.
     */
    private suspend fun ownership(
        mbid: String,
        name: String,
    ): Ownership {
        val nameKey = key(name)
        val verdictKey = name.lowercase() to mbid.lowercase()
        return when {
            nameKey.isEmpty() -> Ownership.UNKNOWN
            else -> verdicts.get(verdictKey) ?: storedOrSearched(mbid, name, nameKey)
        }
    }

    // The verdict the artist cache keeps, now kept in memory too; else the search's, kept in both
    // unless it failed.
    private suspend fun storedOrSearched(
        mbid: String,
        name: String,
        nameKey: String,
    ): Ownership {
        val verdictKey = name.lowercase() to mbid.lowercase()
        // The id first: it holds no space, so the first space ends it.
        val storedKey = "${verdictKey.second} ${verdictKey.first}"
        val stored = stored(storedKey)
        val verdict = stored ?: search(mbid, name, nameKey)
        if (verdict != Ownership.FAILED) verdicts.put(verdictKey, verdict)
        if (stored == null && verdict != Ownership.FAILED) artists.keep(OWNERSHIP_PART, storedKey, verdict.name)
        return verdict
    }

    // The verdict the artist cache keeps for [key]; null for none, or one this version does not know.
    private suspend fun stored(key: String): Ownership? {
        val value = artists.get(OWNERSHIP_PART, key)
        return Ownership.entries.firstOrNull { it.name == value && it != Ownership.FAILED }
    }

    private suspend fun search(
        mbid: String,
        name: String,
        nameKey: String,
    ): Ownership {
        val page = (musicBrainz.searchArtists(name) as? Lookup.Found)?.value ?: return Ownership.FAILED
        val named = page.artists.filter { key(it.name) == nameKey }
        return when {
            page.total < page.artists.size -> Ownership.FAILED
            named.any { !it.mbid.equals(mbid, ignoreCase = true) } -> Ownership.SHARED
            page.total == page.artists.size && named.isNotEmpty() -> Ownership.OWNED
            else -> Ownership.UNKNOWN
        }
    }

    /** The resolved artist for [credited]: the one of that name, or of the same name key. */
    fun resolvedFor(
        credited: String,
        artists: List<ResolvedArtist>,
    ): ResolvedArtist? =
        artists.firstOrNull { it.name == credited }
            ?: artists.firstOrNull { key(it.name) == key(credited) }

    private fun key(name: String): String = accentFolder.decompose(name.lowercase()).filter { it.isLetterOrDigit() }

    companion object {
        /** Searches (name as sent, MusicBrainz id) whose answers are kept in memory. */
        const val VERDICTS: Int = 1024

        /**
         * The search's verdicts in the artist cache (#114 item 4, #238), by the MusicBrainz id and
         * the name as sent, both lowercased, a space between: `OWNED`, `SHARED` or `UNKNOWN`, the
         * names of [Ownership] (which are therefore not renamed). A failure is never kept. The
         * verdict is MusicBrainz's, so it lives for MusicBrainz's lifetime.
         */
        val OWNERSHIP_PART: ArtistPart = ArtistPart("lastfm.ownership", 1, setOf(SourceId.MUSICBRAINZ))
    }
}

/**
 * A map of at most [capacity] entries, which drops the least recently used. Portable: an insertion
 * ordered map, an entry moved to the end when used, behind a [Mutex].
 */
@Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
internal class Lru<K : Any, V : Any>(
    private val capacity: Int,
) {
    private val mutex = Mutex()
    private val map = LinkedHashMap<K, V>()

    suspend fun get(key: K): V? = mutex.withLock { map.remove(key)?.also { map[key] = it } }

    suspend fun put(
        key: K,
        value: V,
    ): Unit =
        mutex.withLock {
            map.remove(key)
            map[key] = value
            if (map.size > capacity) map.remove(map.keys.first())
        }
}
