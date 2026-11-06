package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.text.DateForm

/**
 * The song's insight written while the AI service searches the web for the song itself (#958, *AI web
 * search* set to *Up to 3 times*), when the sources only list it: the song's prompt with its rule on
 * listings, from the evidence as it stands ([evidence]), with the search tool
 * ([ItemPrompt.withSongSearch]), told the track's language ([language]), asked by [WebQuestions]. Each
 * search's pages are evidence lines, each the line [line] gives for it (the app's own search's rules:
 * `SongWebLookup.pageLine`; none, no line), at most [ArtistQuestion.PAGES_PER_SEARCH] of them, numbered
 * after the track's own ([own], whose ids they keep) in the order found; a page another search found,
 * or one of the track's own lines quotes, is not numbered again. What is kept for the track is those
 * pages ([drafts]).
 *
 * *Once* ([query], [once]) is asked only when [WebQuestions] falls back to it, which the song's
 * insight never lets it do (`ItemInsights.searchesItself`: a service with no tools makes the app's own
 * search, `SongWebLookup.search`); it is still answered, as the other questions are.
 */
@Suppress("LongParameterList") // What it asks about, the lines it has, a page's line, and the dates' form.
internal class SongQuestion(
    private val subject: ItemPrompt.Subject,
    private val track: TrackState,
    private val allowModel: Boolean,
    private val evidence: EvidenceSet,
    private val own: EvidenceSet,
    private val hasher: Hasher,
    private val language: String?,
    private val line: (WebPage) -> EvidenceDraft?,
    private val dates: DateForm = DateForm.APP,
) : ItemWebQuestion {
    // The lines of the pages numbered so far, in their order, by address.
    private val numbered = LinkedHashMap<String, EvidenceDraft>()

    override val knowledge: String
        get() = prompt(evidence)

    override val query: String
        get() = songReviewQuery(track.normalized.title, track.normalized.primary)

    // No names ([WebQuestion.subjectNames]): a page is judged as it came, a part naming only the song
    // included, before [line] cleans it.

    override fun once(pages: List<WebPage>): String {
        about(pages).forEach { numbered.putIfAbsent(checkNotNull(it.url), it) }
        return prompt(evidence.with(drafts(), hasher))
    }

    override fun agent(limit: Int): String = ItemPrompt.withSongSearch(knowledge, limit, language)

    override fun toolResult(pages: List<WebPage>): String {
        val before = numbered.keys.toSet()
        val about = about(pages)
        about.forEach { numbered.putIfAbsent(checkNotNull(it.url), it) }
        val shared = own.ids.toSet()
        val found =
            own
                .with(drafts(), hasher)
                .lines
                .filter { it.source == SourceId.WEB && it.id !in shared && it.url !in before }
        return when {
            about.isEmpty() -> NONE_ABOUT
            found.isEmpty() -> ArtistQuestion.ALREADY_GIVEN
            else -> found.joinToString("\n", transform = InsightPrompt::evidenceLine)
        }
    }

    override fun drafts(): List<EvidenceDraft> = numbered.values.toList()

    private fun prompt(lines: EvidenceSet): String =
        ItemPrompt.build(subject, track.nowPlaying, track.normalized, lines, allowModel, dates, onlyListings = true)

    // Of [pages], the lines of those that give one ([line]) and no line of the track's own quotes, at
    // most [ArtistQuestion.PAGES_PER_SEARCH].
    private fun about(pages: List<WebPage>): List<EvidenceDraft> {
        val quoted = own.lines.filter { it.source == SourceId.WEB }.mapNotNullTo(mutableSetOf()) { it.url }
        return pages
            .filter { it.url !in quoted }
            .mapNotNull(line)
            .take(ArtistQuestion.PAGES_PER_SEARCH)
    }

    companion object {
        /** The tool's answer when none of a search's pages is about the song. */
        const val NONE_ABOUT: String = "None of the pages this search found is about the song."
    }
}

/**
 * The words of the song's own search in English (#958): the title and the first [artist], with what
 * finds a page that describes the song rather than lists it.
 */
fun songReviewQuery(
    title: String,
    artist: String,
): String = "\"${InsightPrompt.oneLine(title)}\" ${InsightPrompt.oneLine(artist)} review interview"
