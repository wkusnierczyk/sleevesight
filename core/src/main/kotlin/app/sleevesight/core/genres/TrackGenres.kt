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

/**
 * A label as a row shows it, with its [row], whose level its credit names. As a chip
 * ([TrackGenres.chips]), [weak] when its evidence is weak (#875): a track's tag that no artist's or
 * album's row has, with fewer than [TrackGenres.MIN_VOTES] net votes; its chip is marked, and its
 * genre says why ([TrackGenres.weakEvidence]).
 */
data class PlacedLabel(
    val row: GenreRow,
    val label: TrackLabel,
    val weak: Boolean = false,
)

/**
 * Why a weak genre's evidence is weak (#875, [TrackGenres.weakEvidence]): whether MusicBrainz
 * contributors tagged the track with it ([musicBrainz]; else only Last.fm's tags give it), and the
 * other levels whose tags do not include it: the [artists] with a row of their own (none, one or
 * more), and the album, when it has a row ([album]).
 */
data class WeakEvidence(
    val musicBrainz: Boolean,
    val artists: Int,
    val album: Boolean,
)

/**
 * A track's genre labels (#439; `design/design.md`, *Genre labels*): a row per level that has
 * labels, in [GenreLevel] order, an artist's row for each credited artist with labels, in credit
 * order. A genre found on several levels is on each of their rows. [aiListed] are the genres of
 * the AI's genre list for the track (#762, [withAi]), in its order, by [aiModel], none before it.
 */
data class TrackGenres(
    val rows: List<GenreRow> = emptyList(),
    val aiListed: List<GenreLabel> = emptyList(),
    val aiModel: String? = null,
    val aiSources: List<String> = emptyList(),
) {
    /** Whether no row has a label. */
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The levels that have labels, in [GenreLevel] order. */
    val levels: List<GenreLevel> get() = rows.map { it.level }.distinct()

    /** Whether the labels are of one level only. */
    val oneLevel: Boolean get() = levels.size == 1

    /**
     * The genres as *Genres*' chips show them (#875, replacing #799's fold): every genre of every row
     * (the *Genre tags* table), each once, placed on the first row that has it. First the sources'
     * genres, then those the AI's genre list added ([TrackLabel.aiAdded], #762: the track row's
     * labels with no source's credit), each group in alphabetical order of the names shown, by
     * [names] (the app gives the language's collation; by default, case aside), whatever their
     * levels. A genre on the AI's list that a row has from a source is a source's chip, unmarked
     * (credited to the model in the rows, [withAi]).
     *
     * A source's chip is [PlacedLabel.weak] when it is a track's label whose genre no artist's or
     * album's row has, and its tag has fewer than [MIN_VOTES] net votes ([TrackLabel.votes]:
     * MusicBrainz's; Last.fm's weights are no votes, so a tag only Last.fm gives the track is weak):
     * one contributor's tag on the recording is not backed by the album's and the artists'. With no
     * other level, the same rule: only a tag with [MIN_VOTES] is backed. The rows keep their cap,
     * [GenreVocabulary.MAX_LABELS]; the chips have none of their own.
     */
    fun chips(names: Comparator<String> = CASE_FOLDED): List<PlacedLabel> {
        val others =
            rows
                .filter { it.level != GenreLevel.TRACK }
                .flatMap { it.labels }
                .map { it.genre.identity }
                .toSet()
        val (added, sources) =
            rows
                .flatMap { row -> row.labels.map { PlacedLabel(row, it) } }
                .distinctBy { it.label.genre.identity }
                .partition { it.label.aiAdded }
        val marked =
            sources.map { placed ->
                val label = placed.label
                val weak =
                    placed.row.level == GenreLevel.TRACK &&
                        label.genre.identity !in others &&
                        (label.votes ?: 0) < MIN_VOTES
                placed.copy(weak = weak)
            }
        val byName = compareBy<PlacedLabel, String>(names) { it.label.genre.label }
        val order = byName.thenBy { it.label.genre.identity }
        return marked.sortedWith(order) + added.sortedWith(order)
    }

    /**
     * The genre *Genres*' panel opens on (#875): the first of the [chips], in [names]' order, that is
     * backed, else the first weak one; never one the AI's genre list added; null with no chips.
     */
    fun opening(names: Comparator<String> = CASE_FOLDED): PlacedLabel? {
        val sources = chips(names).filter { !it.label.aiAdded }
        return sources.firstOrNull { !it.weak } ?: sources.firstOrNull()
    }

    /**
     * Why the genre [identity] is weak (#875), when its chip is ([PlacedLabel.weak]); null when it is
     * not, or no chip has it.
     */
    fun weakEvidence(identity: String): WeakEvidence? {
        val chip = chips().firstOrNull { it.label.genre.identity == identity && it.weak } ?: return null
        return WeakEvidence(
            musicBrainz = chip.label.credits.any { it.source == LabelSource.MUSICBRAINZ },
            artists = rows.count { it.level == GenreLevel.ARTIST },
            album = rows.any { it.level == GenreLevel.ALBUM },
        )
    }

    /**
     * These genres with the AI's genre list for the track (#762, `GenreLists`): [genres], listed by
     * [model] (its name, as the AI settings show it). A genre some row has already is credited to the
     * model there too, unmarked and not shown twice in the rows ([TrackLabel.aiModel]), so it may reach
     * a prompt; one no row has joins the track's row, after its labels, with no source's credit
     * ([TrackLabel.aiAdded]), as many as leave the row at most [GenreVocabulary.MAX_LABELS], a
     * track row made for them where there was none. The list is kept
     * ([aiListed]), with the pages it came from when it is from the web
     * ([sources], #801: shown "From {site}" under the chips). Applied twice, the same.
     */
    fun withAi(
        genres: List<GenreLabel>,
        model: String,
        sources: List<String> = emptyList(),
    ): TrackGenres {
        if (genres.isEmpty()) return this
        val listed = genres.map { it.identity }.toSet()
        val have = all.map { it.genre.identity }.toSet()
        val marked =
            rows.map { row ->
                row.copy(labels = row.labels.map { if (it.genre.identity in listed) it.copy(aiModel = model) else it })
            }
        val added =
            genres
                .filter { it.identity !in have }
                .distinctBy { it.identity }
                .map { aiLabel(it, model) }
        val track = marked.firstOrNull { it.level == GenreLevel.TRACK }
        val room = GenreVocabulary.MAX_LABELS - track?.labels.orEmpty().size
        val row = GenreRow(GenreLevel.TRACK, null, track?.labels.orEmpty() + added.take(room.coerceAtLeast(0)))
        return TrackGenres(
            when {
                added.isEmpty() || room <= 0 -> marked
                track == null -> listOf(row) + marked
                else -> marked.map { if (it === track) row else it }
            },
            aiListed = genres,
            aiModel = model,
            aiSources = sources,
        )
    }

    /** Every label of every row, each genre once, in row order: for counting, and the CLI's report. */
    val all: List<TrackLabel> get() = rows.flatMap { it.labels }.distinctBy { it.genre.identity }

    companion object {
        // [genre] as a track's label the AI's genre list by [model] gives, and no source (#762).
        private fun aiLabel(
            genre: GenreLabel,
            model: String,
        ): TrackLabel =
            TrackLabel(
                genre.copy(url = null, level = GenreLevel.TRACK, artist = null, votes = null),
                emptyList(),
                votes = null,
                aiModel = model,
            )

        /**
         * The net votes a track's tag needs for its chip to be backed with no other level agreeing
         * ([chips], #799, #875): two, so that one contributor's tags alone do not.
         */
        const val MIN_VOTES: Int = 2

        /**
         * The chips' order of names where no other is given ([chips], #875): alphabetical, case
         * aside. The app gives the language's collation instead, which also places accented letters
         * with their base letters; ties are then broken by the genres' identities.
         */
        val CASE_FOLDED: Comparator<String> = compareBy { it.lowercase() }
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
