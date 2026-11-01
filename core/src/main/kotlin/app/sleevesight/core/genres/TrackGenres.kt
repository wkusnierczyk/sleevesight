package app.sleevesight.core.genres

/**
 * What a genre label's tags were given to (#439): sources tag the track and its album as well as
 * its artists, and an artist's tags describe a career rather than the track playing. In the order
 * the home screen's header names them, [TRACK], [ARTIST], [ALBUM]: the genres' rows follow it.
 */
enum class GenreLevel {
    TRACK,
    ARTIST,
    ALBUM,
}

/**
 * One row of a track's genres: the labels of one [level], combined over the label sources
 * ([combineLabels]), at most [GenreVocabulary.MAX_LABELS]. An artist's row names its [artist], as
 * the track credits it; the track's and the album's name none.
 */
data class GenreRow(
    val level: GenreLevel,
    val artist: String?,
    val labels: List<TrackLabel>,
)

/** A label as a row shows it, with its [row], whose level its credit names. */
data class PlacedLabel(
    val row: GenreRow,
    val label: TrackLabel,
)

/**
 * A track's genre labels (#439; `design/design.md`, *Genre labels*): a row per level that has
 * labels, in [GenreLevel] order, an artist's row for each credited artist with labels, in credit
 * order. A genre found on several levels is on each of their rows.
 */
data class TrackGenres(
    val rows: List<GenreRow> = emptyList(),
) {
    /** Whether no row has a label. */
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The levels that have labels, in [GenreLevel] order. */
    val levels: List<GenreLevel> get() = rows.map { it.level }.distinct()

    /** Whether the labels are of one level only, so they show as chips with no fold. */
    val oneLevel: Boolean get() = levels.size == 1

    /**
     * The labels shown folded, or with [oneLevel] (#799): the track's that are backed, else the
     * album's, else the artists' together, else, with no other level to give any, the track's all
     * the same; each genre once (the first row giving it), at most [GenreVocabulary.MAX_LABELS]. A
     * track's label is backed with at least [MIN_VOTES] net votes on its tag ([TrackLabel.votes]:
     * MusicBrainz's; Last.fm's weights are no votes), or when the album's or an artist's row has its
     * genre: one contributor's tag on the recording does not outweigh the album's and the artists'.
     * The rows themselves (the *Genre tags* table) keep every label.
     */
    val folded: List<PlacedLabel>
        get() {
            val others =
                rows
                    .filter { it.level != GenreLevel.TRACK }
                    .flatMap { it.labels }
                    .map { it.genre.identity }
                    .toSet()
            val backed =
                placed(GenreLevel.TRACK).filter { (_, label) ->
                    (label.votes ?: 0) >= MIN_VOTES || label.genre.identity in others
                }
            return backed
                .ifEmpty { placed(GenreLevel.ALBUM) }
                .ifEmpty { placed(GenreLevel.ARTIST) }
                .ifEmpty { placed(GenreLevel.TRACK) }
                .distinctBy { it.label.genre.identity }
                .take(GenreVocabulary.MAX_LABELS)
        }

    /** Every label of every row, each genre once, in row order: for counting, and the CLI's report. */
    val all: List<TrackLabel> get() = rows.flatMap { it.labels }.distinctBy { it.genre.identity }

    // [level]'s labels, each with its row, in row order.
    private fun placed(level: GenreLevel): List<PlacedLabel> =
        rows.filter { it.level == level }.flatMap { row -> row.labels.map { PlacedLabel(row, it) } }

    companion object {
        /**
         * The net votes a track's tag needs to lead the folded labels with no other level agreeing
         * ([folded], #799): two, so that one contributor's tags alone do not.
         */
        const val MIN_VOTES: Int = 2
    }
}

/**
 * A track's genres from the labels each label source gave ([bySource]), each with its level and,
 * for an artist's, its artist: a row per level, and per artist for the artists' (in the order of
 * [artists], the track's credited artists; one the list does not name follows them), each row's
 * labels combined as [combineLabels] combines them. Rows with no labels are left out.
 */
fun combineGenres(
    bySource: Map<LabelSource, List<GenreLabel>>,
    artists: List<String>,
): TrackGenres {
    val labels = bySource.values.flatten()
    val named = labels.filter { it.level == GenreLevel.ARTIST }.map { it.artist }.distinct()
    val order = artists.filter { it in named } + named.filter { it !in artists }
    val keys =
        listOf(GenreLevel.TRACK to null) + order.map { GenreLevel.ARTIST to it } + listOf(GenreLevel.ALBUM to null)
    val rows =
        keys.mapNotNull { (level, artist) ->
            val combined =
                combineLabels(
                    bySource.mapValues { (_, given) ->
                        given.filter { it.level == level && (level != GenreLevel.ARTIST || it.artist == artist) }
                    },
                )
            combined.takeIf { it.isNotEmpty() }?.let { GenreRow(level, artist, it) }
        }
    return TrackGenres(rows)
}
