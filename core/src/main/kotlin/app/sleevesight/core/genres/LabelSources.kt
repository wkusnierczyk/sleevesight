package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId

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
 * does. A label with no credits but the model's was added by the AI ([aiAdded]).
 */
data class TrackLabel(
    val genre: GenreLabel,
    val credits: List<LabelCredit>,
    val votes: Int? = genre.votes,
    val aiModel: String? = null,
) {
    /**
     * Whether the AI's genre list added it (#762): no source's tags give it, only the list of
     * [aiModel], so it shows marked **AI**, credited to the model.
     */
    val aiAdded: Boolean get() = aiModel != null && credits.isEmpty()

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
 * #439): every source's labels, Last.fm's first, each in
 * its source's order, at most [GenreVocabulary.MAX_LABELS]. A label another source already gave
 * (the same [GenreLabel.identity]) is not shown twice: it credits both, also when the cap has left
 * no room for a new one.
 *
 * Last.fm's come first because its top tags are weighed over many listeners' tagging, where an
 * artist's MusicBrainz tags are usually a few editors' votes, and because a label only MusicBrainz
 * gives opens no genre card ([TrackLabel.opensCard]), so the cap keeps Last.fm's. MusicBrainz's then
 * fill the room left: at the Phase 0.5 gate they gave labels to 3 of the 30 tracks Last.fm left
 * without, and to 29 of 78 alone (design, *D12*). [bySource] holds the labels of the sources switched on; the
 * order it is given in does not matter.
 */
fun combineLabels(bySource: Map<LabelSource, List<GenreLabel>>): List<TrackLabel> {
    val combined = mutableListOf<TrackLabel>()
    val labelled = LabelSource.entries.flatMap { source -> bySource[source].orEmpty().map { source to it } }
    for ((source, label) in labelled) {
        val at = combined.indexOfFirst { it.genre.identity == label.identity }
        val credit = LabelCredit(source, label.url)
        when {
            at < 0 -> if (combined.size < GenreVocabulary.MAX_LABELS) combined += TrackLabel(label, listOf(credit))
            combined[at].credits.none { it.source == source } -> combined[at] = combined[at].credited(credit, label)
        }
    }
    return combined
}

private fun TrackLabel.credited(
    credit: LabelCredit,
    label: GenreLabel,
): TrackLabel = copy(credits = credits + credit, votes = listOfNotNull(votes, label.votes).maxOrNull())
