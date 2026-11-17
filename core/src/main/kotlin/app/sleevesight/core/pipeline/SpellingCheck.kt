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
 * The sources asked again with the spelling of the title the AI service found on the web (#1293;
 * `design/design.md`, *Spelling check*): MusicBrainz, once, with that title, the player's artists
 * and the player's length ([resolver], as a run resolves). The check corrects the title only: the
 * artists the AI names are never used, so it cannot change who performs. Asked only for a respelling
 * of the player's title ([respelledTitle]):
 *
 * - alike to the player's title, or to a part of it, the player's title carrying words that are not
 *   the title's (" - Turandot", "(From …)"), so another song is never asked for;
 * - with the same numbers ([numbers]: each run of digits and each movement's roman numeral), so
 *   "Symphony No. 5: II. Andante" never becomes No. 6, nor another movement;
 * - adding no artist: the title and the player's artists normalize to the player's artists (a "feat."
 *   in the title would add one).
 *
 * The spelling is kept only when MusicBrainz then accepts a recording crediting one of the player's
 * artists under its own name ([NameMatcher.sameName], never a near name) and with MusicBrainz's id
 * for it (an artist the recording does not credit has none: [agrees]), about the same length (the
 * resolver's own check). One round: what it finds is never checked again.
 */
class SpellingCheck(
    private val resolver: TrackResolver,
    private val normalizer: Normalizer,
    private val names: NameMatcher,
) {
    /**
     * [spelling], found for [nowPlaying] (cleaned for the sources: [played]) by [model] at [writtenAt]
     * (when its answer was written), asked of MusicBrainz: what the run with it starts from
     * ([FoundAs]) when it respells the player's title and MusicBrainz accepts a recording that agrees
     * with the player's, else null.
     */
    suspend fun confirm(
        nowPlaying: NowPlaying,
        played: NormalizedTrack,
        spelling: SearchAnswer.Spelling,
        model: String,
        writtenAt: Instant? = null,
    ): FoundAs? {
        val asked = normalizer.normalize(nowPlaying.copy(title = spelling.title))
        val sameArtists =
            asked.artists.size == played.artists.size &&
                asked.artists.zip(played.artists).all { (a, b) -> names.sameName(a, b) }
        val respelled = sameArtists && respelledTitle(spelling.title, nowPlaying.title, played)
        // Not a respelling of the player's title: MusicBrainz is not asked.
        val resolution = if (respelled) resolver.resolve(asked, nowPlaying.duration) else null
        return resolution
            ?.takeIf { agrees(it, played) }
            ?.let { FoundAs(spelling.title, nowPlaying.artists, played, model, it, writtenAt) }
    }

    /**
     * Whether [resolution] is a recording that agrees with the player's: accepted, and crediting one of
     * [played]'s artists by the same name ([NameMatcher.sameName]), with MusicBrainz's id for it: an
     * artist the recording does not credit is named in the resolution with none.
     */
    fun agrees(
        resolution: Resolution,
        played: NormalizedTrack,
    ): Boolean {
        val outcome = resolution.outcome
        val accepted =
            resolution.resolved &&
                outcome != Outcome.None &&
                outcome != Outcome.Unreachable &&
                outcome !is Outcome.Rejected
        val credited = (resolution.artists + resolution.jointArtists).filter { it.mbid != null }.map { it.name }
        return accepted && credited.any { name -> played.artists.any { names.sameName(it, name) } }
    }

    /**
     * Whether [title] respells the player's [reported] title (as cleaned for the sources: [played]):
     * with the same [numbers] as one of them, and alike to it ([ALIKE]) or to one of its parts, split
     * where a player adds words that are not the title's (" - ", ": ", brackets, " / "). The player's
     * title may carry more than the title; the spelling may not.
     */
    fun respelledTitle(
        title: String,
        reported: String,
        played: NormalizedTrack,
    ): Boolean {
        val whole = listOf(reported, played.title, played.searchTitle)
        val parts = whole.flatMap { it.split(PARTS) }.map(String::trim).filter { names.key(it).isNotEmpty() }
        val sameNumbers = (whole + parts).any { numbers(it) == numbers(title) }
        return sameNumbers && (whole + parts).any { names.titleSimilarity(it, title) >= ALIKE }
    }

    /**
     * The numbers [text] names, as a multiset (sorted): each run of digits (a catalogue, opus or work
     * number, `K. 545`, `BWV 1007`), and each movement's roman numeral (`II.`, `IV:`, `(III)`), in
     * capitals as titles write them.
     */
    fun numbers(text: String): List<String> {
        val digits = DIGITS.findAll(text).map { it.value.trimStart('0').ifEmpty { "0" } }
        val movements = ROMAN.findAll(text).map { it.groupValues[1] }
        return (digits + movements).toList().sorted()
    }

    private companion object {
        /** How alike a respelled title must be to the player's: a letter or two in it. */
        const val ALIKE = 0.75

        /** Where a player's title adds words that are not the title's: " - ", ": ", brackets, " / ". */
        val PARTS = Regex(""" - |: |[()\[\]]| / """)

        /** A run of digits. */
        val DIGITS = Regex("""\d+""")

        /** A movement's roman numeral: capitals alone as a word, followed by a stop, a colon or a bracket. */
        val ROMAN = Regex("""(?<![\p{L}\p{N}])([IVXLC]+)(?=[.:)])""")
    }
}
