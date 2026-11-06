package app.sleevesight.core.genres

import app.sleevesight.core.language.Languages
import app.sleevesight.core.lyrics.LibraryLyrics

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
 * album's row has, with fewer than [TrackGenres.MIN_VOTES] net votes; or Instrumental on a track
 * with signs of vocals (#996, [Singing]); its chip is marked, and its genre says why
 * ([TrackGenres.weakEvidence]).
 */
data class PlacedLabel(
    val row: GenreRow,
    val label: TrackLabel,
    val weak: Boolean = false,
)

/**
 * Why a weak genre's evidence is weak ([TrackGenres.weakEvidence]): a track's tag with few votes
 * (#875, [FewVotes]), or Instrumental on a track with signs of vocals (#996, [SignOfVocals]).
 */
sealed interface WeakEvidence {
    /**
     * A track's tag with few votes (#875): whether MusicBrainz contributors tagged the track with it
     * ([musicBrainz]; else only Last.fm's tags give it), and the other levels whose tags do not
     * include it: the [artists] with a row of their own (none, one or more), and the album, when it
     * has a row ([album]).
     */
    data class FewVotes(
        val musicBrainz: Boolean,
        val artists: Int,
        val album: Boolean,
    ) : WeakEvidence

    /** Instrumental on a track with a sign that it is sung (#996), the strongest sign first. */
    sealed interface SignOfVocals : WeakEvidence

    /** MusicBrainz credits [name] with vocals on the recording. */
    data class Vocalist(
        val name: String,
    ) : SignOfVocals

    /**
     * MusicBrainz gives the song's work a language, [code] (ISO 639-3, not `zxx`), named [name] in
     * English where Sleevesight knows it ([Languages]); null for one it does not, or `mul`.
     */
    data class Language(
        val code: String,
        val name: String?,
    ) : SignOfVocals

    /**
     * The track credits several artists, and [name], one of them (as the track credits it), has no
     * artist's tag of Instrumental, while another artist's row or the album's has it.
     */
    data class Featured(
        val name: String,
    ) : SignOfVocals
}

/**
 * What MusicBrainz, and LRCLIB (#997), say of whether the track is sung (#996; `design/design.md`, *Genre labels*),
 * against which its genre Instrumental ([TrackGenres.INSTRUMENTAL]) is weighed, from the recording's
 * lookup and its work's (`WorkLyrics`). [instrumental]: the recording marked instrumental, or only
 * `zxx` works; then Instrumental stands as tagged, whatever the rest says. Else, [lyrics] (lyrics
 * pages listed for its work, or found by *Find the lyrics*) drops it from every row; and its works'
 * [languages] (ISO 639-3, `zxx` left out) or the artists credited with vocals ([vocalists]) leave it
 * at most weak, on every row. [library] is LRCLIB's answer (#997), when it was asked: lyrics drop
 * Instrumental as lyrics links do, and its instrumental mark keeps it as MusicBrainz's does
 * ([marked]). Where the two disagree, the mark wins: a wrong match must not drop a real Instrumental.
 */
data class Singing(
    val instrumental: Boolean = false,
    val lyrics: Boolean = false,
    val languages: List<String> = emptyList(),
    val vocalists: List<String> = emptyList(),
    val library: LibraryLyrics? = null,
) {
    /**
     * Whether the track is marked instrumental: by MusicBrainz ([instrumental]) or by LRCLIB
     * ([library], #997). Instrumental then stands as tagged, whatever the rest says.
     */
    val marked: Boolean get() = instrumental || library == LibraryLyrics.INSTRUMENTAL

    /**
     * Whether Instrumental is dropped from every row: lyrics found ([lyrics], or LRCLIB's, #997), the
     * track not [marked] instrumental.
     */
    val dropsInstrumental: Boolean get() = (lyrics || library == LibraryLyrics.LYRICS) && !marked

    companion object {
        /** Nothing known: Instrumental stands as tagged. */
        val NONE: Singing = Singing()
    }
}

/**
 * A track's genre labels (#439; `design/design.md`, *Genre labels*): a row per level that has
 * labels, in [GenreLevel] order, an artist's row for each credited artist with labels, in credit
 * order. A genre found on several levels is on each of their rows. [aiListed] are the genres of
 * the AI's genre list for the track (#762, [withAi]), in its order, by [aiModel], none before it.
 * [credited] are the track's credited artists, in credit order, those with no tags too, and
 * [singing] what MusicBrainz says of whether it is sung (#996), against which Instrumental is weighed;
 * [given], the labels each source gave, before they were combined and capped ([combineGenres]), so
 * that lyrics found later combine them again without Instrumental ([withLyricsFound]); and
 * [instrumentalArtists], the artists whose own tags, from any source, include Instrumental, read
 * before the rows' cap ([signOfVocals]'s featured artist).
 */
data class TrackGenres(
    val rows: List<GenreRow> = emptyList(),
    val aiListed: List<GenreLabel> = emptyList(),
    val aiModel: String? = null,
    val aiSources: List<String> = emptyList(),
    val credited: List<String> = emptyList(),
    val singing: Singing = Singing.NONE,
    val given: Map<LabelSource, List<GenreLabel>> = emptyMap(),
    val instrumentalArtists: Set<String> = emptySet(),
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
     * other level, the same rule: only a tag with [MIN_VOTES] is backed. Instrumental is weak, on
     * any row, when the track shows a sign of being sung ([signOfVocals], #996). The rows keep their
     * cap, [GenreVocabulary.MAX_LABELS]; the chips have none of their own.
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
                val fewVotes =
                    placed.row.level == GenreLevel.TRACK &&
                        label.genre.identity !in others &&
                        (label.votes ?: 0) < MIN_VOTES
                placed.copy(weak = fewVotes || signOfVocals(placed) != null)
            }
        // The AI's Instrumental is weighed as a track's (#996): weak with a vocalist or a language.
        val aiMarked = added.map { it.copy(weak = signOfVocals(it) != null) }
        val byName = compareBy<PlacedLabel, String>(names) { it.label.genre.label }
        val order = byName.thenBy { it.label.genre.identity }
        return marked.sortedWith(order) + aiMarked.sortedWith(order)
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
     * Why the genre [identity] is weak (#875), when its chip is ([PlacedLabel.weak]): a sign of vocals
     * where Instrumental has one ([signOfVocals], #996), else few votes; null when it is not weak, or
     * no chip has it.
     */
    fun weakEvidence(identity: String): WeakEvidence? {
        val chip = chips().firstOrNull { it.label.genre.identity == identity && it.weak } ?: return null
        return signOfVocals(chip) ?: WeakEvidence.FewVotes(
            musicBrainz = chip.label.credits.any { it.source == LabelSource.MUSICBRAINZ },
            artists = rows.count { it.level == GenreLevel.ARTIST },
            album = rows.any { it.level == GenreLevel.ALBUM },
        )
    }

    /**
     * The sign that the track is sung that leaves [placed], when it is Instrumental, at most weak
     * (#996; `design/design.md`, *Genre labels*), on whichever row it is: an artist MusicBrainz
     * credits with vocals on the recording; else a language MusicBrainz gives its work; else, placed
     * on an artist's or the album's row, a featured artist: with several credited, one ([credited],
     * other than the row's own) whose tags, from any source, do not include Instrumental, or who has
     * none. Null for any other genre, with none of them, or with the track marked instrumental
     * ([Singing.marked]: by MusicBrainz, or by LRCLIB, #997), where Instrumental stands as tagged.
     * Lyrics found drop it instead ([Singing.dropsInstrumental], [combineGenres]).
     */
    private fun signOfVocals(placed: PlacedLabel): WeakEvidence.SignOfVocals? =
        signOfVocals(placed.label.genre.identity, placed.row)

    /**
     * The sign that the track is sung that leaves the genre [identity], the AI's for the track (#762,
     * #773: *Search with AI*'s genres, shown with no row), at most weak (#996): as for a track's tag,
     * a vocalist or a language, never a featured artist. Null for any other genre, or with none.
     */
    fun aiSignOfVocals(identity: String): WeakEvidence.SignOfVocals? = signOfVocals(identity, null)

    // [signOfVocals] for [identity] on [row]; with none, the track's.
    private fun signOfVocals(
        identity: String,
        row: GenreRow?,
    ): WeakEvidence.SignOfVocals? {
        val weighed = identity == INSTRUMENTAL && !singing.marked
        val vocalist = singing.vocalists.firstOrNull()
        val language = singing.languages.firstOrNull()
        return when {
            !weighed -> null
            vocalist != null -> WeakEvidence.Vocalist(vocalist)
            language != null -> WeakEvidence.Language(language, Languages.ofIso3(language)?.name)
            else -> row?.let { featured(it) }?.let { WeakEvidence.Featured(it) }
        }
    }

    // A featured artist that leaves [row]'s Instrumental weak (#996): with several credited, the first
    // other than an artist's row's own whose tags do not include it, before the rows' cap
    // ([instrumentalArtists], and the rows' own for genres built without it). On the track's row, only
    // for an Instrumental with fewer than [MIN_VOTES] votes whose every backing row, an artist's or the
    // album's, has a featured artist ([backedFeatured]): its backing is weak too.
    private fun featured(row: GenreRow): String? =
        when {
            credited.size < 2 -> null
            row.level == GenreLevel.TRACK -> backedFeatured(row)
            else -> featuredOff(row)
        }

    /**
     * These genres with lyrics found for the track by *Find the lyrics* (#996): Instrumental dropped
     * from every row, as for MusicBrainz's lyrics links ([Singing.dropsInstrumental]), a row left
     * with no label dropped too; unchanged with the track marked instrumental ([Singing.marked]), or
     * its lyrics known already.
     */
    fun withLyricsFound(): TrackGenres {
        if (singing.marked || singing.dropsInstrumental) return this
        val sung = singing.copy(lyrics = true)
        // Combined again without it, so a label it kept out of a full row takes its room; genres built
        // without the sources' labels (previews, tests) lose it from their rows alone.
        val combined =
            if (given.isEmpty()) {
                copy(rows = withoutInstrumental(rows), aiListed = emptyList(), aiModel = null, aiSources = emptyList())
            } else {
                combineGenres(given, credited, sung)
            }
        val found = combined.copy(singing = sung)
        return aiModel?.let { found.withAi(aiListed, it, aiSources) } ?: found
    }

    /**
     * Of [genres], an AI's genre list for the track (#762, #773), those any row may show: all but
     * Instrumental when lyrics were found ([Singing.dropsInstrumental], #996).
     */
    fun aiShown(genres: List<GenreLabel>): List<GenreLabel> =
        if (singing.dropsInstrumental) genres.filter { it.identity != INSTRUMENTAL } else genres

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
        // Lyrics found (#996): Instrumental is on no row, the AI's track row included.
        val listing = aiShown(genres)
        if (genres.isEmpty()) return this
        val listed = listing.map { it.identity }.toSet()
        val have = all.map { it.genre.identity }.toSet()
        val marked =
            rows.map { row ->
                row.copy(labels = row.labels.map { if (it.genre.identity in listed) it.copy(aiModel = model) else it })
            }
        val added =
            listing
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
            aiListed = listing,
            aiModel = model,
            aiSources = sources,
            credited = credited,
            singing = singing,
            given = given,
            instrumentalArtists = instrumentalArtists,
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
         * The identity of the genre Instrumental ([GenreVocabulary.identity] of the vocabulary's tag
         * `instrumental`), weighed against the signs that the track is sung (#996, [Singing]).
         */
        const val INSTRUMENTAL: String = "instrumental"

        // [rows] without Instrumental, a row left with no label dropped (#996).
        internal fun withoutInstrumental(rows: List<GenreRow>): List<GenreRow> =
            rows
                .map { row -> row.copy(labels = row.labels.filter { it.genre.identity != INSTRUMENTAL }) }
                .filter { it.labels.isNotEmpty() }

        /**
         * The chips' order of names where no other is given ([chips], #875): alphabetical, case
         * aside. The app gives the language's collation instead, which also places accented letters
         * with their base letters; ties are then broken by the genres' identities.
         */
        val CASE_FOLDED: Comparator<String> = compareBy { it.lowercase() }
    }
}

// The featured artist of [track]'s Instrumental backed only by rows the featured rule leaves weak
// (#996): its votes fewer than [TrackGenres.MIN_VOTES], and each artist's or album's row with it has one.
private fun TrackGenres.backedFeatured(track: GenreRow): String? {
    val own = track.labels.firstOrNull { it.genre.identity == TrackGenres.INSTRUMENTAL }
    val backing =
        rows.filter { row ->
            row.level != GenreLevel.TRACK &&
                row.labels.any { it.genre.identity == TrackGenres.INSTRUMENTAL }
        }
    val signs = backing.map { featuredOff(it) }
    val weakOwn = own != null && (own.votes ?: 0) < TrackGenres.MIN_VOTES
    return signs.firstOrNull().takeIf { weakOwn && signs.isNotEmpty() && signs.all { it != null } }
}

// The featured artist of an artist's or the album's [row] (#996, `TrackGenres.featured`).
private fun TrackGenres.featuredOff(row: GenreRow): String? {
    val tagged =
        rows
            .filter { artist ->
                artist.level == GenreLevel.ARTIST && artist.labels.any { it.genre.identity == TrackGenres.INSTRUMENTAL }
            }.mapNotNull { it.artist }
            .toSet() + instrumentalArtists
    return credited.firstOrNull { it != row.artist && it !in tagged }
}

/**
 * A track's genres from the labels each label source gave ([bySource]), each with its level and,
 * for an artist's, its artist: a row per level, and per artist for the artists' (in the order of
 * [artists], the track's credited artists; one the list does not name follows them), each row's
 * labels combined as [combineLabels] combines them. Rows with no labels are left out. With
 * [singing] saying lyrics were found (#996, [Singing.dropsInstrumental]), no row has Instrumental,
 * left out before the sources' labels combine, so another source's label may take its room.
 */
fun combineGenres(
    bySource: Map<LabelSource, List<GenreLabel>>,
    artists: List<String>,
    singing: Singing = Singing.NONE,
): TrackGenres {
    val given =
        if (singing.dropsInstrumental) {
            bySource.mapValues { (_, labels) -> labels.filter { it.identity != TrackGenres.INSTRUMENTAL } }
        } else {
            bySource
        }
    val labels = given.values.flatten()
    // A label past its source's cap is on no row; it tells only what the artist's tags include.
    val shown = given.mapValues { (_, labels) -> labels.filter { !it.beyondCap } }
    val named =
        shown.values
            .flatten()
            .filter { it.level == GenreLevel.ARTIST }
            .map { it.artist }
            .distinct()
    val order = artists.filter { it in named } + named.filter { it !in artists }
    val keys =
        listOf(GenreLevel.TRACK to null) + order.map { GenreLevel.ARTIST to it } + listOf(GenreLevel.ALBUM to null)
    val rows =
        keys.mapNotNull { (level, artist) ->
            val combined =
                combineLabels(
                    shown.mapValues { (_, labels) ->
                        labels.filter { it.level == level && (level != GenreLevel.ARTIST || it.artist == artist) }
                    },
                )
            combined.takeIf { it.isNotEmpty() }?.let { GenreRow(level, artist, it) }
        }
    val instrumental =
        labels
            .filter { it.level == GenreLevel.ARTIST && it.identity == TrackGenres.INSTRUMENTAL }
            .mapNotNull { it.artist }
            .toSet()
    return TrackGenres(
        rows,
        credited = artists,
        singing = singing,
        given = bySource,
        instrumentalArtists = instrumental,
    )
}
