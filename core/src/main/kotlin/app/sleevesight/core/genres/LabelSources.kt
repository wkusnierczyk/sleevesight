package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId
import kotlin.math.roundToLong

/**
 * A source whose tags can become genre labels (D12, #84): which of them give labels is a user
 * setting (`Settings.labelSources`). Web search gives no tags, so it is not one; Discogs joins in
 * Phase 3. The order of the entries is the order their labels combine in ([combineLabels]).
 *
 * @property source the evidence source whose lookup brings the tags: a label source gives labels
 *   only while that source is switched on under *Sources*, and Last.fm only with its key.
 * @property credit how a label from it is credited, beside its link.
 * @property licenceUrl the licence of its tags, which the credit links; null where the tags come
 *   under the source's API terms and no licence of their own.
 */
enum class LabelSource(
    val source: SourceId,
    val credit: String,
    val licenceUrl: String?,
) {
    LASTFM(SourceId.LASTFM, "Last.fm", null),

    /**
     * Tags are MusicBrainz's supplementary data, CC BY-NC-SA 3.0 (`design/architecture.md`,
     * *MusicBrainz tags and genres (#9)*, *Conditions*): used for labels only, never sent in a
     * prompt, and each label from them links and credits MusicBrainz.
     */
    MUSICBRAINZ(
        SourceId.MUSICBRAINZ,
        "MusicBrainz contributors, CC BY-NC-SA 3.0",
        "https://creativecommons.org/licenses/by-nc-sa/3.0/",
    ),
}

/**
 * What a request for labels gave (#772): its [labels], and whether a request failed ([failed]: the
 * source could not be reached or answered unusably), which tells "none" from "not known".
 */
data class LabelLookup(
    val labels: List<GenreLabel>,
    val failed: Boolean = false,
)

/** A label source that gave a label, with the page whose tags gave it ([GenreLabel.url]). */
data class LabelCredit(
    val source: LabelSource,
    val url: String?,
)

/**
 * A genre label as a track shows it: [genre], as the first source to give it has it, and
 * [credits], each source whose tags gave it, in [LabelSource] order; [votes], the most net votes a
 * source that counts them gave its tag ([GenreLabel.votes], #799), null where none does; [aiModel],
 * the AI model whose genre list for the track has it (#762, [TrackGenres.withAi]), null where none
 * does. A label with no credits but the model's was added by the AI ([aiAdded]). [describedIn], for
 * a genre an artist's description names (#1000, [describedGenres]), where: such a label has no
 * credits either, and is no tag. [markedBy], for a genre a source that gives no tags marks the track
 * with (#1024: LRCLIB's instrumental mark, `TrackGenres.withLibraryMark`), that source: such a label
 * has no credits and no votes, and is a source's genre all the same, credited to it under *Tag
 * sources*, never marked AI.
 */
data class TrackLabel(
    val genre: GenreLabel,
    val credits: List<LabelCredit>,
    val votes: Int? = genre.votes,
    val aiModel: String? = null,
    val describedIn: DescribedIn? = null,
    val markedBy: SourceId? = null,
) {
    /**
     * Whether the AI's genre list added it (#762): no source's tags give it, only the list of
     * [aiModel], so it shows marked **AI**, credited to the model; not one a source's mark gave
     * ([markedBy], #1024), which the list only credits to the model too.
     */
    val aiAdded: Boolean get() = aiModel != null && credits.isEmpty() && describedIn == null && markedBy == null

    /**
     * Whether it may open AI-written genre card content: an artist's label (as before the track's
     * and album's labels came, #439) that a source other than MusicBrainz gives. A label only
     * MusicBrainz gives (D12) is display-only, opening its MusicBrainz link and credit, so its tags
     * never reach a prompt (`design/design.md`, *Genre labels*); the track's and the album's open
     * none either. Both unless #101 decides otherwise.
     */
    val opensCard: Boolean
        get() = genre.level == GenreLevel.ARTIST && credits.any { it.source != LabelSource.MUSICBRAINZ }
}

/**
 * How the labels of several sources combine (#84), on one row of a track's genres ([combineGenres],
 * #439), weighed together before the cap (#1019): every label of every source, each weighing its
 * [GenreLabel.weight] (its tag's count over the page's top tag's, so each source's top tag weighs 1,
 * MusicBrainz's votes and Last.fm's weight out of 100 alike), or, where the source gave no counts,
 * its place among its source's labels (1 for the first, down by an equal step to the last); a genre
 * several sources give weighs the sum of their weights, so agreement is what lifts it, with no
 * bonus of its own. A genre only MusicBrainz gives counts at half its weight (`MUSICBRAINZ_ALONE`),
 * since it can never reach a prompt (D12) and should make room for the other sources'. The
 * [GenreVocabulary.MAX_LABELS] that weigh most are kept, once, after the sources combine; at equal
 * weight, in the order of the sources ([LabelSource], Last.fm's first) and each source's own. A
 * label another source also gave (the same [GenreLabel.identity]) is not shown twice: it credits
 * both.
 *
 * Last.fm's come first at equal weight because its top tags are weighed over many listeners'
 * tagging, where an artist's MusicBrainz tags are usually a few editors' votes, and because a label
 * only MusicBrainz gives opens no genre card ([TrackLabel.opensCard]). Before #1019 each source's
 * labels were capped at five in its own order and Last.fm's filled the row first, so a genre both
 * sources gave weighed no more than one only a single source gave. [bySource] holds the labels of
 * the sources switched on; the order it is given in does not matter.
 */
fun combineLabels(bySource: Map<LabelSource, List<GenreLabel>>): List<TrackLabel> {
    val combined = mutableListOf<TrackLabel>()
    val weights = mutableMapOf<String, Double>()
    for (source in LabelSource.entries) {
        val labels = bySource[source].orEmpty()
        labels.forEachIndexed { at, label ->
            val identity = label.identity
            val i = combined.indexOfFirst { it.genre.identity == identity }
            val credit = LabelCredit(source, label.url)
            val weight = label.weight ?: placeWeight(at, labels.size)
            when {
                i < 0 -> combined += TrackLabel(label, listOf(credit))
                combined[i].credits.none { it.source == source } -> combined[i] = combined[i].credited(credit, label)
                // The same source's genre twice on one row: weighed once, as its first.
                else -> return@forEachIndexed
            }
            weights[identity] = weights.getOrDefault(identity, 0.0) + weight
        }
    }
    // A stable sort on the weights rounded to [WEIGHT_STEPS]ths, so sums equal but for floating-point
    // rounding (0.1 + 0.2 against 0.3) are equal: at equal weight, the order the labels were first given in.
    return combined
        .sortedByDescending { label ->
            val weight = weights.getValue(label.genre.identity)
            val share = if (label.credits.all { it.source == LabelSource.MUSICBRAINZ }) MUSICBRAINZ_ALONE else 1.0
            (weight * share * WEIGHT_STEPS).roundToLong()
        }.take(GenreVocabulary.MAX_LABELS)
}

// The share of its weight a genre counts at when every credit on the row's label is MusicBrainz's
// (#1019): such a genre never reaches a prompt (D12), so it makes room for the genres another source
// gives. A genre MusicBrainz shares with another source keeps MusicBrainz's full weight in its sum.
private const val MUSICBRAINZ_ALONE: Double = 0.5

// The steps of weight [combineLabels] tells apart: a millionth, far finer than any count's share
// (Last.fm's 1 of 100, a vote of MusicBrainz's thousands), far coarser than a double's rounding.
private const val WEIGHT_STEPS: Double = 1_000_000.0

// The weight of a label its source gave no count for: by its place, 1 for the first of [size] and
// down by an equal step, so the last of five weighs 0.2.
private fun placeWeight(
    at: Int,
    size: Int,
): Double = (size - at).toDouble() / size

private fun TrackLabel.credited(
    credit: LabelCredit,
    label: GenreLabel,
): TrackLabel = copy(credits = credits + credit, votes = listOfNotNull(votes, label.votes).maxOrNull())
