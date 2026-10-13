package io.github.wkusnierczyk.sleevesight.sources.lastfm

import io.github.wkusnierczyk.sleevesight.core.genres.GenreLabel
import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist

/**
 * How Last.fm's answer for one credited artist was judged (`design/architecture.md`, *Source
 * adapters*, *Last.fm verification*).
 */
sealed interface LastFmMatch {
    /** Last.fm's artist has the MusicBrainz id resolution gave the credited artist: its lines may be cited. */
    data object Verified : LastFmMatch

    /** Same name, and nothing to check it against: its lines are shown, never cited (D4). */
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
}

/**
 * One credited artist's Last.fm lookup: the name as credited, the judgement, Last.fm's artist when
 * it answered with one, and the genre labels from its tags (none unless verified or unverified).
 */
data class LastFmArtistLookup(
    val credited: String,
    val match: LastFmMatch,
    val artist: LastFmArtist?,
    val genres: List<GenreLabel>,
)

/**
 * The check of Last.fm's [artist] against the credited name and the resolved artist, in this order:
 *
 * 1. Both have a MusicBrainz id: the same id verifies the match, even under a corrected name; a
 *    different one rejects it.
 * 2. Otherwise a name Last.fm corrected (it differs beyond case, accents, spacing and punctuation)
 *    is rejected: autocorrection is how a small act's name lands on an unrelated band's page.
 * 3. Otherwise the match is unverified.
 *
 * Name matching folds case and accents and keeps letters and digits only.
 */
internal class ArtistCheck(
    private val accentFolder: AccentFolder,
) {
    fun judge(
        credited: String,
        artist: LastFmArtist,
        resolved: ResolvedArtist?,
    ): LastFmMatch {
        val resolvedMbid = resolved?.mbid?.takeIf { it.isNotBlank() }
        val lastFmMbid = artist.mbid
        return when {
            resolvedMbid != null && lastFmMbid != null -> {
                if (resolvedMbid.equals(lastFmMbid, ignoreCase = true)) {
                    LastFmMatch.Verified
                } else {
                    LastFmMatch.Rejected("Last.fm's ${artist.name} is a different MusicBrainz artist from $credited")
                }
            }

            key(artist.name) != key(credited) -> {
                LastFmMatch.Rejected("Last.fm corrected $credited to ${artist.name}, and no MusicBrainz id confirms it")
            }

            else -> {
                LastFmMatch.Unverified
            }
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
}
