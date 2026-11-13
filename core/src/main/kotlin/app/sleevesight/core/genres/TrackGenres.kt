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
 * the track credits it; the track's and the album's name none. [described]: an artist's row of the
 * genres their description names (#1000, [TrackGenres.described]), not of their tags.
 */
data class GenreRow(
    val level: GenreLevel,
    val artist: String?,
    val labels: List<TrackLabel>,
    val described: Boolean = false,
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
     * MusicBrainz credits [name] with the words of the song's work (`lyricist`, #998): a sign of vocals
     * weighed as [Vocalist] is, weak with its line, the check of the track's vocals still wanted.
     */
    data class Lyricist(
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

    /**
     * Instrumental only an artist's description gives (#1000, #998's artist hint), [where] it says so,
     * with no source saying so of the track: weak until a check of the track's vocals settles it
     * (#998, [TrackGenres.artistHint]).
     */
    data class ArtistHint(
        val where: DescribedIn,
    ) : WeakEvidence

    /**
     * Instrumental that only LRCLIB's instrumental mark (#997) adds (#1024, [TrackLabel.markedBy]) or
     * lifts from a sign of vocals or the artist's hint (#1055, [Singing.libraryAlone]): LRCLIB's records
     * are submitted by its users, so its mark alone does not make the genre firm.
     */
    data object LibraryMark : WeakEvidence
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
 * The song's work's writing credits (#998): the [lyricists], a sign as the vocalists are; and whether
 * it credits composers alone ([composersOnly]), a hint that the track is instrumental, which only
 * starts the check of its vocals. [checked] is that check's answer (#998, [VocalsAnswer]), when it was
 * applied: instrumental lifts what left Instrumental weak ([lifted]); vocals drop it, as lyrics do.
 */
data class Singing(
    val instrumental: Boolean = false,
    val lyrics: Boolean = false,
    val languages: List<String> = emptyList(),
    val vocalists: List<String> = emptyList(),
    val library: LibraryLyrics? = null,
    val lyricists: List<String> = emptyList(),
    val composersOnly: Boolean = false,
    val checked: VocalsAnswer? = null,
) {
    /**
     * Whether the track is marked instrumental: by MusicBrainz ([instrumental]) or by LRCLIB
     * ([library], #997). Instrumental then stands as tagged, whatever the rest says.
     */
    val marked: Boolean get() = instrumental || library == LibraryLyrics.INSTRUMENTAL

    /**
     * Whether what would leave Instrumental weak is lifted: the track [marked] instrumental, or the
     * check of its vocals found it described as instrumental (#998, [checked]).
     */
    val lifted: Boolean get() = marked || checked == VocalsAnswer.INSTRUMENTAL

    /**
     * Whether only LRCLIB's mark lifts what would leave Instrumental weak (#1055): marked instrumental
     * by LRCLIB ([library]), neither by MusicBrainz ([instrumental]) nor found so by the check of its
     * vocals ([checked]). LRCLIB's records are submitted by its users, so the Instrumental the mark adds
     * or lifts shows weak ([WeakEvidence.LibraryMark]); it still settles the paid check, and still wins
     * over lyrics ([dropsInstrumental]).
     */
    val libraryAlone: Boolean
        get() = library == LibraryLyrics.INSTRUMENTAL && !instrumental && checked != VocalsAnswer.INSTRUMENTAL

    /**
     * Whether the work's composers alone hint that the track is instrumental (#998): [composersOnly],
     * with no free sign that it is sung, no vocal credit ([vocalists]) and no language of its work
     * ([languages]). What starts the check of its vocals, LRCLIB first, with no Instrumental on any row.
     */
    val composersHint: Boolean get() = composersOnly && vocalists.isEmpty() && languages.isEmpty()

    /**
     * Whether Instrumental is dropped from every row: lyrics found ([lyrics], or LRCLIB's, #997), or
     * vocals the check of them found (#998, [checked]), the track not [marked] instrumental.
     */
    val dropsInstrumental: Boolean
        get() = (lyrics || library == LibraryLyrics.LYRICS || checked == VocalsAnswer.VOCALS) && !marked

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
 * before the rows' cap ([signOfVocals]'s featured artist). [descriptions] are the rows of the genres
 * the artists' descriptions name (#1000, `withDescribed`; shown as [described]), apart from the
 * tags' [rows], so that what
 * reads the tags (the prompts' genres, the backing of a weak tag, the AI's genre list) does not take
 * them for tags; [table] and [chips] show them beside the tags.
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
    val descriptions: List<GenreRow> = emptyList(),
) {
    /**
     * The rows of the genres the artists' descriptions name (#1000), as shown: of each artist's
     * [descriptions] (every genre their lines name, `describedGenres`), Instrumental left out when
     * lyrics were found ([Singing.dropsInstrumental]) and those their own tags' row has left out,
     * then at most [GenreVocabulary.MAX_LABELS], a row left with none dropped. Worked out from the
     * rows as they are, so a genre a rebuilt row gains, or room Instrumental leaves, shows at once.
     */
    val described: List<GenreRow> by lazy {
        descriptions.mapNotNull { row ->
            val own =
                rows
                    .filter { it.level == GenreLevel.ARTIST && it.artist == row.artist }
                    .flatMap { it.labels }
                    .map { it.genre.identity }
                    .toSet()
            val labels =
                row.labels
                    .filter { !singing.dropsInstrumental || it.genre.identity != INSTRUMENTAL }
                    .filter { it.genre.identity !in own }
                    .take(GenreVocabulary.MAX_LABELS)
            labels.takeIf { it.isNotEmpty() }?.let { row.copy(labels = it, described = true) }
        }
    }

    /** Whether no row has a label, of the tags or of a description. */
    val isEmpty: Boolean get() = rows.isEmpty() && described.isEmpty()

    /**
     * Whether a row has a source's tag or a genre the AI's list added: a label other than one a
     * source's mark gave (#1024, [TrackLabel.markedBy]: LRCLIB's Instrumental). What *Search with AI*
     * for the genres reads (#773): a track whose only genre is the mark's still has none of its tags,
     * so the search stays offered, as under the genres an artist's description names (#1000).
     */
    val hasTags: Boolean get() = rows.any { row -> row.labels.any { it.markedBy == null } }

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
     * any row, when the track shows a sign of being sung ([signOfVocals], #996); found instrumental by
     * the check of its vocals (#998, [Singing.checked]), it is weak for neither its signs nor its
     * votes, nor is the Instrumental LRCLIB's mark adds ([TrackLabel.markedBy], #1024,
     * `withLibraryMark`), a source's chip with no votes. Yet LRCLIB's records are submitted by its
     * users, so an Instrumental that only its mark adds or lifts from a sign of vocals or the artist's
     * hint is weak ([WeakEvidence.LibraryMark], #1055), on any row, the AI's too. The rows keep their cap,
     * [GenreVocabulary.MAX_LABELS]; the chips have none of their own.
     *
     * The genres the artists' descriptions name ([described], #1000) follow the sources' tags, before
     * the AI's, in the same order of names, each placed on its artist's described row: only those no
     * tag and no AI's genre gives (a tag, or the AI's genre list, wins). Instrumental among them is
     * the artist's hint (#998): weak, with a sign of vocals as any Instrumental, else with no source
     * saying so of the track ([artistHint]).
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
                // Instrumental the check of the track's vocals found so (#998) is not weak for its votes, nor
                // one a source's mark gave (#1024: LRCLIB's), which has none.
                val checked = label.genre.identity == INSTRUMENTAL && singing.checked == VocalsAnswer.INSTRUMENTAL
                val confirmed = checked || label.markedBy != null
                val fewVotes =
                    placed.row.level == GenreLevel.TRACK &&
                        label.genre.identity !in others &&
                        (label.votes ?: 0) < MIN_VOTES &&
                        !confirmed
                placed.copy(weak = fewVotes || signOfVocals(placed) != null || libraryMark(placed) != null)
            }
        // The AI's Instrumental is weighed as a track's (#996): weak with a vocalist or a language.
        val aiMarked = added.map { it.copy(weak = signOfVocals(it) != null || libraryMark(it) != null) }
        val tagged = (sources + added).map { it.label.genre.identity }.toSet()
        val text =
            described
                .flatMap { row -> row.labels.map { PlacedLabel(row, it) } }
                .distinctBy { it.label.genre.identity }
                .filter { it.label.genre.identity !in tagged }
                .map { placed ->
                    val hinted = artistHint(placed) != null || libraryMark(placed) != null
                    placed.copy(weak = signOfVocals(placed) != null || hinted)
                }
        val byName = compareBy<PlacedLabel, String>(names) { it.label.genre.label }
        val order = byName.thenBy { it.label.genre.identity }
        return marked.sortedWith(order) + text.sortedWith(order) + aiMarked.sortedWith(order)
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
     * where Instrumental has one ([signOfVocals], #996), else, for Instrumental only an artist's
     * description gives, the artist's hint (#1000, [artistHint]), else, for Instrumental only LRCLIB's
     * mark adds or lifts, that mark (#1055, [libraryMark]), else few votes; null when it is not
     * weak, or no chip has it.
     */
    fun weakEvidence(identity: String): WeakEvidence? {
        val chip = chips().firstOrNull { it.label.genre.identity == identity && it.weak } ?: return null
        return signOfVocals(chip) ?: artistHint(chip) ?: libraryMark(chip) ?: WeakEvidence.FewVotes(
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
     * none; after a vocalist, a lyricist MusicBrainz credits on the song's work (#998). Null for any
     * other genre, with none of them, or with the track marked instrumental ([Singing.marked]: by
     * MusicBrainz, or by LRCLIB, #997) or found so by the check of its vocals (#998,
     * [Singing.lifted]), where Instrumental stands as tagged.
     * Lyrics found drop it instead ([Singing.dropsInstrumental], [combineGenres]).
     */
    private fun signOfVocals(placed: PlacedLabel): WeakEvidence.SignOfVocals? =
        signOfVocals(placed.label.genre.identity, placed.row)

    /**
     * Why the genre [identity], the AI's for the track (#762, #773: *Search with AI*'s genres, shown
     * with no row), is weak: the sign that the track is sung that leaves it at most weak (#996), as for
     * a track's tag, a vocalist, a lyricist (#998) or a language, never a featured artist; or, where
     * only LRCLIB's mark lifts such a sign, that mark (#1055, [WeakEvidence.LibraryMark]). Null for any
     * other genre, or with none.
     */
    fun aiWeakEvidence(identity: String): WeakEvidence? =
        signOfVocals(identity, null)
            ?: WeakEvidence.LibraryMark.takeIf {
                singing.libraryAlone && signOfVocals(identity, null, lifted = false) != null
            }

    // [signOfVocals] for [identity] on [row]; with none, the track's; [lifted], unless weighed without
    // what lifts it (#1055's [libraryMark]).
    internal fun signOfVocals(
        identity: String,
        row: GenreRow?,
        lifted: Boolean = singing.lifted,
    ): WeakEvidence.SignOfVocals? {
        val weighed = identity == INSTRUMENTAL && !lifted
        val vocalist = singing.vocalists.firstOrNull()
        val lyricist = singing.lyricists.firstOrNull()
        val language = singing.languages.firstOrNull()
        return when {
            !weighed -> null
            vocalist != null -> WeakEvidence.Vocalist(vocalist)
            lyricist != null -> WeakEvidence.Lyricist(lyricist)
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
        val found = combined.copy(singing = sung, descriptions = descriptions)
        return aiModel?.let { found.withAi(aiListed, it, aiSources) } ?: found
    }

    /**
     * The rows as *Genre tags* shows them (#786, #1000): [rows], each artist's described row right
     * after their tags' row; one whose artist has no tags' row after the artists' rows, before the
     * album's.
     */
    val table: List<GenreRow>
        get() {
            if (described.isEmpty()) return rows
            val left = described.toMutableList()
            val shown = mutableListOf<GenreRow>()
            for (row in rows) {
                if (row.level == GenreLevel.ALBUM) {
                    shown += left
                    left.clear()
                }
                shown += row
                if (row.level == GenreLevel.ARTIST) {
                    left.firstOrNull { it.artist == row.artist }?.let {
                        shown += it
                        left.remove(it)
                    }
                }
            }
            return shown + left
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
            descriptions = descriptions,
        )
    }

    /** Every label of every row, each genre once, in row order: for counting, and the CLI's report. */
    val all: List<TrackLabel> get() = rows.flatMap { it.labels }.distinctBy { it.genre.identity }

    /**
     * Every genre a chip may show (#1000): [all], then those only the artists' descriptions name
     * ([described]), each genre once; what a genre's panel is found among.
     */
    val everyLabel: List<TrackLabel>
        get() = (all + described.flatMap { it.labels }).distinctBy { it.genre.identity }

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

/**
 * This vocabulary's label of Instrumental ([TrackGenres.INSTRUMENTAL]) as a track's: what the check of
 * the track's vocals (#998) and LRCLIB's instrumental mark (#1024) add where no row has it.
 */
fun GenreVocabulary.instrumental(): GenreLabel? =
    labelsFor(listOf(TrackGenres.INSTRUMENTAL), level = GenreLevel.TRACK).firstOrNull()

/**
 * LRCLIB's mark as why [placed], Instrumental, is weak (#1055): the mark alone backs it
 * ([Singing.libraryAlone]), for the Instrumental it adds ([TrackLabel.markedBy], #1024), or one it
 * lifts that a sign of vocals or the artist's hint would leave weak without it. Null for any other
 * chip, with the track marked instrumental by MusicBrainz or found so by the check of its vocals, or
 * where the mark lifts nothing (a source's Instrumental with no sign against it stands as before).
 */
private fun TrackGenres.libraryMark(placed: PlacedLabel): WeakEvidence.LibraryMark? {
    val instrumental = placed.label.genre.identity == TrackGenres.INSTRUMENTAL
    val lifts =
        placed.label.markedBy != null ||
            signOfVocals(TrackGenres.INSTRUMENTAL, placed.row, lifted = false) != null ||
            artistHint(placed, lifted = false) != null
    return WeakEvidence.LibraryMark.takeIf { instrumental && singing.libraryAlone && lifts }
}

/**
 * The artist's hint that leaves [placed], Instrumental from an artist's description alone (#1000,
 * #998), weak: no source says so of the track. Null for any other chip, or with the track marked
 * instrumental ([Singing.marked]: by MusicBrainz, or by LRCLIB, #997), which says so, or found so by
 * #998's check of the track's vocals ([Singing.lifted]); vocals it found drop the genre instead.
 */
private fun TrackGenres.artistHint(
    placed: PlacedLabel,
    lifted: Boolean = singing.lifted,
): WeakEvidence.ArtistHint? {
    val where = placed.label.describedIn
    return if (where != null && placed.label.genre.identity == TrackGenres.INSTRUMENTAL && !lifted) {
        WeakEvidence.ArtistHint(where)
    } else {
        null
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
