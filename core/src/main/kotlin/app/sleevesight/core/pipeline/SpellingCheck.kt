package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.SearchAnswer

/**
 * The sources asked again with the spelling the AI service found on the web (#1293; `design/design.md`,
 * *Spelling check*): MusicBrainz, once, with the title and artists in that spelling and the player's
 * length ([resolver], as a run resolves). The spelling is kept only when MusicBrainz then accepts a
 * recording that agrees with the player's: the same performers (one of the track's credited artists
 * is among the recording's, as the player names them or as respelled), about the same length (the
 * resolver's own check, against the player's length). Its artists are taken only when each is a
 * respelling of the player's at the same place ([respelling]), so a wrong guess cannot bring another
 * performer in; otherwise the player's artists stay. One round: what it finds is never checked again.
 */
class SpellingCheck(
    private val resolver: TrackResolver,
    private val normalizer: Normalizer,
    private val names: NameMatcher,
) {
    /**
     * [spelling], found for [nowPlaying] (cleaned for the sources: [played]), asked of MusicBrainz:
     * what the run with it starts from ([FoundAs], named by [model]) when MusicBrainz accepts a
     * recording that agrees with the player's, else null.
     */
    suspend fun confirm(
        nowPlaying: NowPlaying,
        played: NormalizedTrack,
        spelling: SearchAnswer.Spelling,
        model: String,
    ): FoundAs? {
        val artists =
            spelling.artists
                .takeIf { respelling(it, played.artists) }
                ?.joinToString(", ")
                ?: nowPlaying.artists
        val asked = normalizer.normalize(nowPlaying.copy(title = spelling.title, artists = artists))
        val resolution = resolver.resolve(asked, nowPlaying.duration)
        return if (agrees(resolution, played, asked)) {
            FoundAs(spelling.title, artists, played, model, resolution)
        } else {
            null
        }
    }

    /**
     * Whether [resolution] is a recording that agrees with the player's: accepted, and crediting one
     * of [played]'s artists, or of [asked]'s (their respelling).
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
        val credited = (resolution.artists + resolution.jointArtists).map { it.name }
        val ours = (played.artists + asked.artists).distinct()
        return accepted && credited.any { name -> ours.any { names.sameName(it, name) } }
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
        /** How alike a respelled artist must be to the player's: a letter or two in a name. */
        const val ALIKE = 0.75
    }
}
