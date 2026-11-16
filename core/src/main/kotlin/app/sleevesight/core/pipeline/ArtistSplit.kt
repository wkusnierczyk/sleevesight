package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.WorkLyrics

/**
 * A track's artists split between a Composer row and an Artist row on Home (#1199,
 * `design/classical.md`, section 3): made only when the roles ([ArtistRoles], #1180) tell a composer
 * apart from at least one performer; otherwise the track keeps one Artist row, as before.
 *
 * @property composers the credited artists who composed the work, in the track's order; the Composer
 *   panel is about the first ([composer]).
 * @property words who wrote the work's words, apart from its composers: the `lyricist`s, then the
 *   `librettist`s (#1274), of the work MusicBrainz looks up ([WorkLyrics.lyricists],
 *   [WorkLyrics.librettists]), by MusicBrainz's names; empty when it names none, or when that is not
 *   known.
 * @property lead the performer the Artist panel is about ([ArtistRoles.lead]).
 * @property others the other performers, in the track's order: the chips under *Also performing on
 *   this recording*.
 * @property name what the Composer row is called ([AuthorsName]).
 * @property classical whether the song's row is called *Music* (#1331), by the test that names the
 *   Composer row: whenever it is *Composer*, and with *Authors* when the work is a classical one
 *   ([ArtistRoles.classical], or a title leading with its composer); never with *Author*.
 */
data class ArtistSplit(
    val composers: List<String>,
    val words: List<String>,
    val lead: String,
    val others: List<String>,
    val name: AuthorsName,
    val classical: Boolean = false,
) {
    /** The composer the Composer panel is about. */
    val composer: String get() = composers.first()

    /** Who wrote the work: its composers, then who wrote its words. */
    val authors: List<String> get() = composers + words

    /** The performers, the lead first. */
    val performers: List<String> get() = listOf(lead) + others

    companion object {
        /**
         * [track]'s split by its [roles], with the writing credits of its work, [lyrics] (null when not
         * known), names compared by [same]; null when the roles tell no composer, or no performer.
         *
         * The Composer row is *Authors* with more than one author: two composers, or a lyricist or a
         * librettist apart from the composer (MusicBrainz's `lyricist` and `librettist`, #1274: an
         * aria's composer and its librettists). With one, *Author* when the work's lyricist (or
         * librettist) is the composer and the work is no classical one ([ArtistRoles.classical], nor a
         * title leading with its composer: a songwriter's own words); otherwise, the words theirs on a
         * classical work, none, or not known, *Composer*.
         */
        fun of(
            track: NormalizedTrack,
            roles: ArtistRoles,
            lyrics: WorkLyrics?,
            same: (String, String) -> Boolean = { a, b -> a.trim().equals(b.trim(), ignoreCase = true) },
        ): ArtistSplit? {
            val composers = roles.composers.filter(String::isNotBlank).distinct()
            val performers = roles.performers.filter { it.isNotBlank() && it !in composers }.distinct()
            if (composers.isEmpty() || performers.isEmpty()) return null
            val lead = roles.lead(track).takeIf { it in performers } ?: performers.first()
            // A work's lyricists and librettists alike (#1274); one credited as both is one author.
            val wordsBy = lyrics?.let { it.lyricists + it.librettists }.orEmpty().filter(String::isNotBlank)
            val words = wordsBy.filter { author -> composers.none { same(it, author) } }.distinct()
            val ownWords = wordsBy.any { author -> composers.any { same(it, author) } }
            val classicalWork = roles.classical || track.composer != null
            val name =
                when {
                    composers.size + words.size > 1 -> AuthorsName.AUTHORS
                    ownWords && !classicalWork -> AuthorsName.AUTHOR
                    else -> AuthorsName.COMPOSER
                }
            // A Composer row always comes with Music, an Author row never (#1331).
            val classical = name == AuthorsName.COMPOSER || (name == AuthorsName.AUTHORS && classicalWork)
            return ArtistSplit(composers, words, lead, performers - lead, name, classical)
        }
    }
}

/** What the Composer row is called (#1199). */
enum class AuthorsName {
    /** One composer, the words theirs or none: a classical work. */
    COMPOSER,

    /** One author of a work that is not classical. */
    AUTHOR,

    /** More than one: music and words by different people, or two composers. */
    AUTHORS,
}

/**
 * [this] track's split between a Composer row and an Artist row (#1199, [ArtistSplit.of]): by its
 * roles ([TrackState.roles]), with its work's writing credits as MusicBrainz's resolution gives them,
 * else as its result kept from an earlier play holds them; names compared by [same].
 */
fun TrackState.artistSplit(
    same: (String, String) -> Boolean = { a, b -> a.trim().equals(b.trim(), ignoreCase = true) },
): ArtistSplit? {
    val lyrics =
        resolution?.takeIf { it.resolved }?.lyrics
            ?: (sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.lyrics
    // Nothing known of the work's credits is as none given.
    return ArtistSplit.of(normalized, roles, lyrics?.takeIf { it != WorkLyrics.NONE }, same)
}
