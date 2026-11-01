package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.InsightPrompt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * A genre's reason asked again with web search (#801, path A of #762's pick): the track's evidence
 * could not support the genre, so the same question is asked with the pages a web search finds, each
 * a line of web evidence numbered after the track's own (`S6`, `S7` after `S1` to `S5`,
 * [EvidenceSet.with]), which the reason cites as it cites the others. *Once* searches [query] (the
 * title, the artists and the genre's label, a genre a source other than MusicBrainz gives or the AI's
 * list named, never one only MusicBrainz's tags give: D12) and gives its pages in the evidence;
 * *Up to 3 times* gives the model the search tool ([GenreReasonPrompt.withSearch]), each search's
 * pages as evidence lines ([toolResult]). [evidence] is then what the reply is checked against: the
 * track's and every page numbered, as the prompt gave them.
 */
internal class ReasonQuestion(
    private val track: TrackState,
    private val label: String,
    private val base: EvidenceSet,
    private val allowModel: Boolean,
    private val hasher: Hasher,
    private val at: Instant,
) : WebQuestion {
    // The pages numbered so far, in their order, by address.
    private val numbered = LinkedHashMap<String, WebPage>()

    override val knowledge: String
        get() = GenreReasonPrompt.build(track.nowPlaying, track.normalized, base, label, allowModel)

    override val query: String
        get() {
            val artists = track.normalized.artists.joinToString(", ")
            return "\"${InsightPrompt.oneLine(track.normalized.title)}\" by ${InsightPrompt.oneLine(artists)}, " +
                "${InsightPrompt.oneLine(label)} genre"
        }

    override fun once(pages: List<WebPage>): String {
        pages.forEach { numbered.putIfAbsent(it.url, it) }
        return GenreReasonPrompt.build(track.nowPlaying, track.normalized, evidence(), label, allowModel)
    }

    override fun agent(limit: Int): String =
        GenreReasonPrompt.withSearch(knowledge, limit, firstId = "${SourceId.WEB.tag}${webLines(base) + 1}")

    override fun toolResult(pages: List<WebPage>): String {
        val before = numbered.size
        pages.forEach { numbered.putIfAbsent(it.url, it) }
        val found = evidence().lines.filter { it.source == SourceId.WEB }.drop(webLines(base) + before)
        return if (found.isEmpty()) {
            "These pages are among the evidence lines already given."
        } else {
            found.joinToString("\n", transform = InsightPrompt::evidenceLine)
        }
    }

    /** The pages numbered so far, in their order. */
    val pages: List<WebPage> get() = numbered.values.toList()

    /** The track's evidence with every page numbered so far after its own lines. */
    fun evidence(): EvidenceSet = withPages(base, pages, hasher, at)

    companion object {
        /** [base] with [pages] as web lines after its own, retrieved [at]. */
        fun withPages(
            base: EvidenceSet,
            pages: List<WebPage>,
            hasher: Hasher,
            at: Instant,
        ): EvidenceSet = base.with(pages.map { EvidenceDraft(SourceId.WEB, Scope.WEB, it.text, it.url, at) }, hasher)

        // The web lines [set] has.
        private fun webLines(set: EvidenceSet): Int = set.lines.count { it.source == SourceId.WEB }
    }
}

/**
 * What a reason asked again with web search keeps in the synthesis cache (#801): its [reply], and
 * the [pages] its searches found, in the order they were numbered, so a later read checks it
 * against the same lines. Marked [MARK]; a text without it is a reply as kept before, checked
 * against the track's evidence alone.
 */
@Serializable
internal class KeptReasonText(
    val reply: String,
    val pages: List<KeptPage>,
    @SerialName(MARK) val mark: Int = 1,
) {
    fun text(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val MARK: String = "sleevesight_reason"

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** The kept reply in [text], or null when it is a reply as kept before (not marked). */
        fun of(text: String): KeptReasonText? =
            if (!text.contains("\"$MARK\"")) {
                null
            } else {
                @Suppress("SwallowedException") // Not one: read as a reply.
                try {
                    JSON.decodeFromString(serializer(), text)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }
}

/** A page a reason found, as kept: its address, title and passage. */
@Serializable
internal class KeptPage(
    val url: String,
    val title: String? = null,
    val text: String,
) {
    fun page(): WebPage = WebPage(url, title, text)

    companion object {
        fun of(page: WebPage): KeptPage = KeptPage(page.url, page.title, page.text)
    }
}
