package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.SearchAnswer
import kotlin.time.Instant

/**
 * The sources asked again with the spelling the AI service found on the web (#1293; `design/design.md`,
 * *Spelling check*): MusicBrainz, once, with the title and artists in that spelling and the player's
 * length ([resolver], as a run resolves). Asked only for a respelling of the player's track:
 *
 * - its title alike to the player's ([respelledTitle]): to the whole title, or to a part of it, the
 *   player's title carrying words that are not the title's (" - Turandot", "(From …)"), so another
 *   song of the same artist is never asked for;
 * - its artists, as the title and artists normalize (a "feat." in the title adds one), each a
 *   respelling of the player's at the same place ([respelling]), so no other performer comes in; the
 *   AI's artists that are no respelling are dropped for the player's own.
 *
 * The spelling is kept only when MusicBrainz then accepts a recording that agrees with the player's:
 * crediting one of the track's artists by MusicBrainz's own id (an artist the recording does not
 * credit has none: [agrees]), about the same length (the resolver's own check, against the player's
 * length). One round: what it finds is never checked again.
 */
class SpellingCheck(
    private val resolver: TrackResolver,
    private val normalizer: Normalizer,
    private val names: NameMatcher,
) {
    /**
     * [spelling], found for [nowPlaying] (cleaned for the sources: [played]) by [model] at [writtenAt]
     * (when its answer was written), asked of MusicBrainz: what the run with it starts from
     * ([FoundAs]) when it respells the player's track and MusicBrainz accepts a recording that agrees
     * with the player's, else null.
     */
    suspend fun confirm(
        nowPlaying: NowPlaying,
        played: NormalizedTrack,
        spelling: SearchAnswer.Spelling,
        model: String,
        writtenAt: Instant? = null,
    ): FoundAs? {
        val artists =
            spelling.artists
                .takeIf { respelling(it, played.artists) }
                ?.joinToString(", ")
                ?: nowPlaying.artists
        val asked = normalizer.normalize(nowPlaying.copy(title = spelling.title, artists = artists))
        val respelled =
            respelledTitle(spelling.title, nowPlaying.title, played) && respelling(asked.artists, played.artists)
        // Not a respelling of the player's track: MusicBrainz is not asked.
        val resolution = if (respelled) resolver.resolve(asked, nowPlaying.duration) else null
        return resolution
            ?.takeIf { agrees(it, played, asked) }
            ?.let { FoundAs(spelling.title, artists, played, model, it, writtenAt) }
    }

    /**
     * Whether [resolution] is a recording that agrees with the player's: accepted, and crediting one of
     * [played]'s artists, or of [asked]'s (their respelling), with MusicBrainz's id for it: an artist
     * the recording does not credit is named in the resolution with none.
     */
    fun agrees(
        resolution: Resolution,
        played: NormalizedTrack,
        asked: NormalizedTrack,
    ): Boolean {
        val outcome = resolution.outcome
        val accepted =
            resolution.resolved &&
                outcome != Outcome.None &&
                outcome != Outcome.Unreachable &&
                outcome !is Outcome.Rejected
        val credited = (resolution.artists + resolution.jointArtists).filter { it.mbid != null }.map { it.name }
        val ours = (played.artists + asked.artists).distinct()
        return accepted && credited.any { name -> ours.any { names.sameName(it, name) } }
    }

    /**
     * Whether [title] respells the player's [reported] title (as cleaned for the sources: [played]):
     * alike to it ([ALIKE]), or to one of its parts, split where a player adds words that are not the
     * title's (" - ", ": ", brackets, " / "). The player's title may carry more than the title; the
     * spelling may not.
     */
    fun respelledTitle(
        title: String,
        reported: String,
        played: NormalizedTrack,
    ): Boolean {
        val whole = listOf(reported, played.title, played.searchTitle)
        val parts = whole.flatMap { it.split(PARTS) }.map(String::trim).filter { names.key(it).isNotEmpty() }
        return (whole + parts).any { names.titleSimilarity(it, title) >= ALIKE }
    }

    /**
     * Whether [respelled] are [artists] respelled: as many, and each alike to the one at its place
     * ([NameMatcher.titleSimilarity] at least [ALIKE]).
     */
    fun respelling(
        respelled: List<String>,
        artists: List<String>,
    ): Boolean =
        respelled.isNotEmpty() &&
            respelled.size == artists.size &&
            respelled.zip(artists).all { (a, b) -> names.titleSimilarity(a, b) >= ALIKE }

    private companion object {
        /** How alike a respelled name must be to the player's: a letter or two in a name. */
        const val ALIKE = 0.75

        /** Where a player's title adds words that are not the title's: " - ", ": ", brackets, " / ". */
        val PARTS = Regex(""" - |: |[()\[\]]| / """)
    }
}
