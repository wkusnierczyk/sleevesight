package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.ExplainPrompt
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind

/**
 * What the song is about, asked with web search (#908, *Explain the song*; [ExplainPrompt]): about
 * [facts], from the pages numbered in one run, `S1`, `S2` … ([ExplainPrompt.id]): first [base], the
 * track's own pages about the song ([of]), then each page a search finds that is not among them yet,
 * in the order found. *Once* searches [query] and gives every page with the question ([once]);
 * *Up to 3 times* gives [base] with the question and each search's new pages as the tool's answer
 * ([toolResult]). [sources] is then what the reply's citations are read against, and what is kept
 * with it.
 */
internal class ExplainQuestion(
    private val facts: SearchFacts,
    base: List<WebPage>,
) : WebQuestion {
    // The pages numbered so far, in their order, by address.
    private val numbered = LinkedHashMap<String, WebPage>().apply { base.forEach { putIfAbsent(it.url, it) } }

    // The track's own pages, given with every prompt.
    private val given = numbered.values.toList()

    override val knowledge: String
        get() = ExplainPrompt.knowledge(facts, given)

    override val query: String
        get() = ExplainPrompt.query(facts)

    override fun once(pages: List<WebPage>): String {
        pages.forEach { numbered.putIfAbsent(it.url, it) }
        return ExplainPrompt.once(facts, numbered.values.toList())
    }

    override fun agent(limit: Int): String = ExplainPrompt.agent(facts, given, limit)

    override fun toolResult(pages: List<WebPage>): String {
        val before = numbered.size
        pages.forEach { numbered.putIfAbsent(it.url, it) }
        val found = numbered.values.drop(before)
        return if (found.isEmpty()) ALREADY_GIVEN else ExplainPrompt.pages(found, first = before)
    }

    /** The pages numbered so far, each with its id, address and title. */
    val sources: List<CitedPage>
        get() = numbered.values.mapIndexed { i, page -> CitedPage(ExplainPrompt.id(i), page.url, page.title) }

    companion object {
        /** The tool's answer when every page of a search is among those given already. */
        const val ALREADY_GIVEN: String = "These pages are among the pages already given."

        /** The scopes of the evidence lines about the song: the recording's, a cover's original's, the web's. */
        private val ABOUT_THE_SONG = setOf(Scope.SONG, Scope.ORIGINAL_SONG, Scope.WEB)

        /** The sources whose lines are no page about the song: MusicBrainz's (D12), the player's, the model's. */
        private val NO_PAGE = setOf(SourceId.MUSICBRAINZ, SourceId.PLAYER, SourceId.MODEL)

        /**
         * The question about [facts], from [evidence]'s pages about the song ([pagesOf]), for [kind]
         * [SearchKind.EXPLAIN]; null for any other kind, which `AiSearches` asks as its own.
         */
        fun of(
            kind: SearchKind,
            facts: SearchFacts,
            evidence: EvidenceSet?,
        ): ExplainQuestion? = if (kind == SearchKind.EXPLAIN) ExplainQuestion(facts, pagesOf(evidence)) else null

        /**
         * [evidence]'s lines about the song (not the artist's, nor the release's) that have a page,
         * none of MusicBrainz's, as pages, one per address, in the evidence's order: its lines'
         * texts joined, titled by its source ("Wikipedia"), or untitled for the web search's own.
         */
        fun pagesOf(evidence: EvidenceSet?): List<WebPage> =
            evidence
                ?.lines
                .orEmpty()
                .filter { it.scope in ABOUT_THE_SONG && it.source !in NO_PAGE && !it.url.isNullOrBlank() }
                .groupBy { checkNotNull(it.url) }
                .map { (url, lines) ->
                    val source = lines.first().source
                    val title = InsightPrompt.sourceLabel(source).takeIf { source != SourceId.WEB }
                    WebPage(url, title, lines.joinToString(" ") { it.text })
                }
    }
}
