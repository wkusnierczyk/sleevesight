package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.pipeline.linesBeyond
import app.sleevesight.core.pipeline.showsInstrumental
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.sameAddress
import app.sleevesight.core.text.SourceText
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

// The album's own lines in *Sources* (#522), and the artist's web lines (#912), so a citation of the
// album's or the artist's insight opens *Sources* at its line, as the key insight's do.

/**
 * What *Sources* lists ([evidence]): the track's evidence, then, once the album's insight has
 * gathered its own on this track, the album's lines mixed in by source, each after its source's track
 * lines and numbered on from them (W1, W2 the track's, W3 the album's; D1 the album's first Wikidata
 * line when the track has none). The track's lines keep their ids. The numbering is for showing alone:
 * the album's insight is written from, and keyed by, its own lines numbered on their own. [album]
 * holds the ids of the album's lines as shown, whose scope says "album"; [purposes], those of the web
 * lines named by what they were read for (#1205, [LinePurpose]: "genres"), in place of their scope.
 * [asked] is [evidence] before those pages joined it (#1205): what a genre's reason is asked and keyed
 * on (`reasonedOn`), so pages listed for showing never change a reason's key, nor cost one again.
 * [moved] holds, by address and passage, the pages left out as repeats (#1434, [withoutRepeats]) and
 * the id of the line that holds each one's passage whole, which a citation of it shows and opens
 * ([webLineOf]).
 */
internal class SourcesLines(
    val evidence: EvidenceSet?,
    val album: Set<String> = emptySet(),
    val purposes: Map<String, LinePurpose> = emptyMap(),
    val asked: EvidenceSet? = evidence,
    val moved: Moved = emptyMap(),
)

/**
 * What a web line in *Sources* was read for, named in place of its scope (#1205): *genres · host*
 * for a page the AI's genre list rests on; *vocals check · host* for the page the check of the
 * track's vocals cites (#1053); *explanation · host* for a page the song's explanation cites (#1067).
 * A few words each, [label], so another check's pages can be named the same way.
 */
internal enum class LinePurpose(
    @param:StringRes val label: Int,
) {
    GENRES(R.string.home_line_purpose_genres),
    VOCALS_CHECK(R.string.home_line_purpose_vocals_check),
    EXPLANATION(R.string.home_line_purpose_explanation),
}

/**
 * What *Sources* names [line] by, before its host: what it was read for ([SourcesLines.purposes],
 * #1205), "album" for the album's own line (#522), else its scope ([scopeText]).
 */
@Composable
internal fun SourcesLines.what(line: EvidenceLine): String =
    purposes[line.id]?.let { stringResource(it.label) }
        ?: if (line.id in album) stringResource(R.string.home_scope_album) else scopeText(line.scope)

/**
 * [lines] with [pages] as web lines after them, each named [purpose] (#1205), numbered on from the
 * web lines they have (`S7` after `S6`); a page whose address a web line has already (`sameAddress`)
 * is that line, not listed twice, and keeps its own name. The song's explanation (#1067,
 * [LinePurpose.EXPLANATION]) also cites the track's own pages of other sources (its Wikipedia
 * article): a page any line has the address of is that line ([lineOf]). With [passages], the pages'
 * texts being passages of them (#1434), a page whose passage a line already listed, or a longer page
 * of [pages], holds whole is left out ([withoutRepeats]), its address and passage kept in
 * [SourcesLines.moved] with that line's id, before each address is kept once, so a distinct passage
 * after a repeat at the same address is listed; each passage shows whole, one cut mid-sentence after
 * "…" ([shownPassage]). A page whose text is its address has no passage: never left out, shown as it is.
 * [lines] itself with no page to add, or no evidence to add them to.
 */
internal fun withPages(
    lines: SourcesLines,
    pages: List<WebPage>,
    purpose: LinePurpose,
    passages: Boolean = false,
): SourcesLines {
    val evidence = lines.evidence
    val web =
        evidence
            ?.lines
            .orEmpty()
            .filter { purpose == LinePurpose.EXPLANATION || it.source == SourceId.WEB }
            .mapNotNull { it.url }
    // A page at an address a web line has already is that line (#1205).
    val unlisted = pages.filter { page -> web.none { sameAddress(it, page.url) } }
    // A page that repeats another's text (iHeart's AllMusic's), a passage cut mid-sentence (#1434),
    // read before each address is kept once, so when the first page at an address repeats a line,
    // the next there with a passage of its own is listed. A page whose text is its address (of a list
    // kept before passages were) has no passage: never a repeat, nor held by a line, shown as it is.
    val (bare, read) = unlisted.partition { !passages || addressOnly(it.text, it.url) }
    val held = evidence?.lines.orEmpty().filterNot { line -> line.url?.let { addressOnly(line.text, it) } == true }
    val repeats = withoutRepeats(read, held)
    val passageKept = repeats.kept.toSet()
    // Each address once, as `sameAddress` reads them (#1205): a page the list and a reason's search
    // both found, one with a trailing slash, is one line; the pages keep their order.
    val fresh =
        unlisted
            .filter { it in bare || it in passageKept }
            .fold(listOf<WebPage>()) { kept, page ->
                if (kept.any { sameAddress(it.url, page.url) }) kept else kept + page
            }
    if (evidence == null || (fresh.isEmpty() && repeats.repeated.isEmpty())) return lines
    val drafts =
        fresh.map {
            val text = if (passages && it !in bare) shownPassage(it.text) else it.text
            EvidenceDraft(SourceId.WEB, Scope.WEB, text, it.url, UNDATED)
        }
    val shown = evidence.with(drafts, JvmHasher)
    val own = evidence.ids.toSet()
    val named = shown.lines.filter { it.id !in own }.associate { it.id to purpose }
    // Each page left out at the line that holds its passage: a line listed, or the page kept that does.
    val moved =
        repeats.repeated
            .mapNotNull { repeat ->
                val line = repeat.line ?: repeat.within?.let { webLineOf(it.url, shown) }
                line?.let { MovedPage(repeat.page.url, passageKey(repeat.page.text)) to it }
            }.toMap()
    return SourcesLines(shown, lines.album, lines.purposes + named, lines.asked, lines.moved + moved)
}

/**
 * The id of the line [sources] (*Sources*' lines) shows for the line [id] of [evidence], a reason's
 * own (#1205): left out as a repeat, the line that holds its passage ([moved], #1434, by its address
 * and passage); a web page's by its address ([webLineOf]), whatever its id in [evidence]; else the
 * same source, scope and words ([shownId]); null for none, which shows no citation at all, never
 * its own id that *Sources* may number another line by. A reason keeps its own ids, as it was
 * checked and kept; only what shows them is mapped, so a citation lands on its page however
 * *Sources* numbers it.
 */
internal fun shownLine(
    id: String,
    evidence: EvidenceSet?,
    sources: EvidenceSet?,
    moved: Moved = emptyMap(),
): String? {
    val line = evidence?.get(id) ?: return null
    val page = line.url?.takeIf { line.source == SourceId.WEB }?.let { webLineOf(it, sources, moved, line.text) }
    return page ?: shownId(id, evidence, sources)
}

/**
 * The id of the line [lines] shows the page at [url] under: for its [passage] left out as a repeat
 * (#1434), the line that holds it ([moved], `SourcesLines.moved`, by address and passage), first, so
 * a line listed later at the address (an explanation's page, its title alone) never takes its place;
 * else the web line at its address (`sameAddress`); null for none. A page whose passage is unknown
 * finds no line it was left out for.
 */
internal fun webLineOf(
    url: String,
    lines: EvidenceSet?,
    moved: Moved = emptyMap(),
    passage: String? = null,
): String? =
    moved.holderOf(url, passage)
        ?: lines
            ?.lines
            ?.firstOrNull { it.source == SourceId.WEB && it.url?.let { at -> sameAddress(at, url) } == true }
            ?.id

/**
 * [evidence], the track's, with [album]'s lines (#522) and [artist]'s, the artist's insight's (its web
 * lines for an artist the sources say nothing about, #912), and [song]'s, the song's insight's (its web
 * lines for a song the sources only list, #958), and the [others]', the composer's and the artists' a
 * chip opened (#1199, each line about its artist), mixed in by source and numbered on from the track's; a
 * line the track, or the album, has already (its source, scope and words) once (`linesBeyond`). The
 * artist's lines say "artist" as their scope does. [taken] are the ids of [evidence]'s lines its run
 * took from the album's (#1131, `TrackState.albumIds`): they say "album" from that alone, whatever
 * [album] holds now.
 */
@Suppress("LongParameterList") // The track's lines, and those each item gathered beyond them.
internal fun withAlbum(
    evidence: EvidenceSet?,
    album: EvidenceSet?,
    artist: EvidenceSet? = null,
    song: EvidenceSet? = null,
    taken: Set<String> = emptySet(),
    others: List<EvidenceSet> = emptyList(),
): SourcesLines {
    val more = linesBeyond(evidence?.lines.orEmpty(), listOf(album, artist) + others + song)
    if (evidence == null || (more.isEmpty() && taken.isEmpty())) return SourcesLines(evidence)
    val shown = evidence.with(more, JvmHasher)
    val albums = album?.lines.orEmpty().mapTo(mutableSetOf()) { Triple(it.source, it.scope, it.text) }
    val own = evidence.ids.toSet()
    val albumIds =
        shown.lines
            .filter { it.id !in own && Triple(it.source, it.scope, it.text) in albums }
            .mapTo(taken.toMutableSet()) { it.id }
    return SourcesLines(shown, albumIds)
}

/**
 * What *Sources* lists for [track]: its evidence, with its album's lines, its artist's web lines
 * (#912), the composer's and the chips' lines (#1199) and its song's (#958), once [notes] gathered
 * them; then the pages the AI's genre list from [genres] rests on (#1205), once it lists any genre,
 * and those the genres' reasons found on the web
 * (#801, [GenreNotes.searchedPages]), all named *genres* and numbered together, each address once;
 * last, the page the check of the track's vocals cites (#1053, [GenreNotes.vocals]), named *vocals
 * check*, while the genres *Genres* composes ([composedGenres], *Find the lyrics*' answer from
 * [search] with them) still weigh its answer ([checkedPage]), and not again when another line has its
 * address; after it, the pages the song's explanation from [search] cites (#1067, [explainedPages]),
 * fresh or kept, named *explanation*, each address once; last, under an empty *Genres*, the pages
 * the reasons of *Ask AI*'s genres cite (#952, [answeredGenrePages]), named *genres*. A reason's
 * citations, the check's and the explanation's show these ids by address ([shownLine], [webLineOf],
 * [lineOf]), so they never point at another page.
 */
@Composable
internal fun sourcesLines(
    notes: ItemNotes,
    track: TrackState?,
    genres: GenreNotes = GenreNotes.None,
    search: AiSearchFeed = AiSearchFeed.None,
): SourcesLines {
    // Followed afresh for another track, or another album of it (the same song on a compilation).
    val album by remember(notes, track?.normalized, track?.nowPlaying?.album, track?.resolution, track?.sources) {
        track?.let(notes::albumEvidence) ?: flowOf(null)
    }.collectAsState(null)
    val artist by remember(notes, track?.normalized, track?.resolution, track?.sources) {
        track?.let(notes::artistEvidence) ?: flowOf(null)
    }.collectAsState(null)
    val song by remember(notes, track?.normalized, track?.resolution, track?.sources) {
        track?.let(notes::songEvidence) ?: flowOf(null)
    }.collectAsState(null)
    // The composer's and the chips' (#1199), each line about its artist.
    val others by remember(notes, track?.normalized, track?.resolution, track?.sources) {
        track?.let(notes::othersEvidence) ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val listed =
        track
            ?.let { aiGenres(it, genres) }
            ?.takeIf { it.genres.isNotEmpty() }
            ?.pages
            .orEmpty()
    // And the pages the genres' reasons found on the web (#801), numbered with the list's, after them.
    val searched by remember(genres, track?.normalized, track?.nowPlaying?.duration) {
        track?.let(genres::searchedPages) ?: flowOf(emptyList())
    }.collectAsState(emptyList())
    val pages = listed + searched
    val evidence = track?.evidence
    val taken = track?.albumIds.orEmpty()
    val genreLines =
        remember(evidence, album, artist, song, taken, others, pages) {
            val all = withAlbum(evidence, album, artist, song, taken, others)
            withPages(all, pages, LinePurpose.GENRES, passages = true)
        }
    // The genres as *Genres* composes them, the artists' descriptions read from these lines.
    val composed = composedGenres(track, PanelNotes(genres, lines = genreLines.evidence), PanelSearch(search, track))
    val checked = listOfNotNull(checkedPage(composed))
    // The song's explanation (#1067), as *Lyrics* shows it, read only: fresh or kept, the same pages.
    val explained by remember(search, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album) {
        track?.let { search.answered(it, SearchKind.EXPLAIN) } ?: flowOf(AiSearch.None)
    }.collectAsState(AiSearch.None)
    val explanation = explainedPages(explained)
    // The pages the reasons of *Ask AI*'s genres cite (#952), read only, under an empty *Genres* alone.
    val asked by remember(search, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album) {
        track?.let { search.answered(it, SearchKind.GENRES) } ?: flowOf(AiSearch.None)
    }.collectAsState(AiSearch.None)
    val reasoned = answeredGenrePages(asked, track).takeIf { track?.genres?.hasTags != true }.orEmpty()
    return remember(genreLines, checked, explanation, reasoned) {
        val explained =
            withPages(
                withPages(genreLines, checked, LinePurpose.VOCALS_CHECK, passages = true),
                explanation,
                LinePurpose.EXPLANATION,
            )
        // A page any line has the address of (the track's Wikipedia article) is that line, not a new one.
        withPages(explained, reasoned.filter { lineOf(it.url, explained.evidence) == null }, LinePurpose.GENRES)
    }
}

/**
 * The pages the song's explanation in [search] cites (#1067), in the order first cited, as web lines
 * of *Sources* list them: each its title ([shownTitle]), its site ([siteOf]) where it has none; none
 * for no answer.
 */
internal fun explainedPages(search: AiSearch): List<WebPage> {
    val answer = (search as? AiSearch.Answered)?.answer as? SearchAnswer.Explanation ?: return emptyList()
    return answer.pages.map { page -> WebPage(page.url, page.title, shownTitle(page.title) ?: siteOf(page.url)) }
}

/**
 * A page's [title] as a line of *Sources* shows it: cleaned as its evidence is (`SourceText.titleOf`:
 * without what a person does not see, a tag character, a zero-width space or a right-to-left
 * override among it; a joiner that shapes an emoji or a Persian word kept), its spaces one; null for
 * none, or one with nothing left to show, so a kept title of invisible characters alone falls back
 * to the site as a fresh one does.
 */
internal fun shownTitle(title: String?): String? =
    SourceText
        .titleOf(title)
        ?.replace(BLANKS, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * The page the check of the track's vocals rests on (#1053), while [composed], the genres *Genres*
 * shows, still weigh its answer: `instrumental` while Instrumental shows (lyrics found since, by
 * MusicBrainz, LRCLIB or *Find the lyrics*, drop it, and the answer with it); `vocals` once taken,
 * as it drops Instrumental itself. None for `unknown`, or no check.
 */
internal fun checkedPage(composed: ComposedGenres): WebPage? {
    val checked = composed.vocals?.state as? VocalsState.Checked ?: return null
    return when (checked.answer) {
        VocalsAnswer.VOCALS -> checked.page
        VocalsAnswer.INSTRUMENTAL -> checked.page?.takeIf { composed.track?.genres?.showsInstrumental() == true }
        VocalsAnswer.UNKNOWN -> null
    }
}

/**
 * The id of the line [sources] shows for the line [id] of [evidence], one of an item's (#522): the
 * same source, scope and words; null for none.
 */
internal fun shownId(
    id: String,
    evidence: EvidenceSet,
    sources: EvidenceSet?,
): String? {
    val line = evidence[id] ?: return null
    return sources
        ?.lines
        ?.firstOrNull { it.source == line.source && it.scope == line.scope && it.text == line.text }
        ?.id
}

// When a page the AI's genre list rests on was retrieved, which *Sources* does not show: not kept.
private val UNDATED = Instant.fromEpochMilliseconds(0)

// Spaces and control characters (a tab, a line break), each run read as one space; classes ICU and
// the JVM read alike.
private val BLANKS = Regex("[\\p{Cc}\\p{Zs}]+")
