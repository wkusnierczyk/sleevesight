package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.text.DateForm
import kotlin.time.Instant

/**
 * The artist's insight written while the AI service searches the web for the artist itself (#912,
 * *AI web search* set to *Up to 3 times*), when the sources say nothing about the artist: the
 * artist's prompt ([ItemPrompt.build], from no evidence) with the search tool ([ItemPrompt.withSearch]),
 * asked by [WebQuestions]. Each search's pages that name the artist ([namesArtist]), at most
 * [PAGES_PER_SEARCH] of them, are evidence lines numbered from `S1` in the order found ([toolResult]),
 * each the line [line] gives for it (the app's own search's rules, `ArtistWebLookup.pageLine`: no page
 * of a source the app reads itself, no page boilerplate, cut as a web line is; none, no line),
 * each about the artist (web, scope artist); a page another search found already is not numbered
 * again. What the reply is checked against is those lines ([evidence]), and what is kept for the
 * artist is those pages ([drafts]), retrieved [at] the write.
 *
 * *Once* ([query], [once]) is asked only when [WebQuestions] falls back to it, which the artist's
 * insight never lets it do (`ItemInsights.searchesItself`: a service with no tools makes the app's
 * own search, `ArtistWebLookup.search`); it is still answered, as the other questions are.
 */
@Suppress("LongParameterList") // What it asks about, how names are read, a page's line, the time and the dates' form.
internal class ArtistQuestion(
    private val subject: ItemPrompt.Subject,
    private val track: TrackState,
    private val allowModel: Boolean,
    private val names: NameMatcher,
    private val hasher: Hasher,
    private val at: Instant,
    private val line: (WebPage) -> String? = { it.text },
    // The form the insight writes its dates in (`InsightPrompt.dateRule`).
    private val dates: DateForm = DateForm.APP,
) : WebQuestion {
    // The lines of the pages numbered so far, in their order, by address.
    private val numbered = LinkedHashMap<String, EvidenceDraft>()

    override val knowledge: String
        get() = prompt(EvidenceSet.of(emptyList(), hasher))

    override val query: String
        get() = "\"${InsightPrompt.oneLine(subject.name)}\", music artist"

    // No names ([WebQuestion.subjectNames]): a page is judged by the artist's name in its passage as it
    // came, a part naming only the artist included, before [line] cleans it for the artist (#905).

    override fun once(pages: List<WebPage>): String {
        about(pages).forEach { numbered.putIfAbsent(checkNotNull(it.url), it) }
        return prompt(evidence())
    }

    override fun agent(limit: Int): String = ItemPrompt.withSearch(knowledge, limit)

    override fun toolResult(pages: List<WebPage>): String {
        val before = numbered.size
        val about = about(pages)
        about.forEach { numbered.putIfAbsent(checkNotNull(it.url), it) }
        val found = evidence().lines.drop(before)
        return when {
            about.isEmpty() -> NONE_ABOUT
            found.isEmpty() -> ALREADY_GIVEN
            else -> found.joinToString("\n", transform = InsightPrompt::evidenceLine)
        }
    }

    /** The pages numbered so far, as the artist's web lines, in their order. */
    fun drafts(): List<EvidenceDraft> = numbered.values.toList()

    /** The pages numbered so far, as evidence lines numbered from `S1`, as the tool gave them. */
    fun evidence(): EvidenceSet = EvidenceSet.of(drafts(), hasher)

    // The artist's prompt from [evidence].
    private fun prompt(evidence: EvidenceSet): String =
        ItemPrompt.build(subject, track.nowPlaying, track.normalized, evidence, allowModel, dates)

    // Of [pages], the lines of those that name the artist and give one ([line]), at most [PAGES_PER_SEARCH].
    private fun about(pages: List<WebPage>): List<EvidenceDraft> =
        pages
            .filter(::namesArtist)
            .mapNotNull { page ->
                line(
                    page,
                )?.takeIf { it.isNotBlank() }?.let { EvidenceDraft(SourceId.WEB, Scope.ARTIST, it, page.url, at) }
            }.take(PAGES_PER_SEARCH)

    // Whether [page] names the artist, its whole name, in its title or its passage, as the names fold.
    private fun namesArtist(page: WebPage): Boolean {
        val artist = names.key(subject.name)
        return artist.isNotBlank() && " $artist " in " ${names.key("${page.title.orEmpty()} ${page.text}")} "
    }

    companion object {
        /**
         * The most pages of one search numbered as evidence lines: the most lines the web search
         * source keeps for a track or an album (`ExaWebSearch.MAX_LINES`), so three searches give
         * at most fifteen.
         */
        const val PAGES_PER_SEARCH: Int = 5

        /** The tool's answer when none of a search's pages names the artist. */
        const val NONE_ABOUT: String = "None of the pages this search found names the artist."

        /** The tool's answer when every page of a search is among the evidence lines given already. */
        const val ALREADY_GIVEN: String = "These pages are among the evidence lines already given."
    }
}
