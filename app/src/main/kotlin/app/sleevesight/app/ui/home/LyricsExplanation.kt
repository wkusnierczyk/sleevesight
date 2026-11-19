package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.sleevesight.app.R
import app.sleevesight.app.ui.openWebLink
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SentenceStatus
import app.sleevesight.core.synthesis.sameAddress

// *Explain the song*'s answer in the *Lyrics* panel (#908): its sentences, cited as an insight's are,
// each citation the page's line in *Sources* (#1067); and, last, *Read more* with the pages' sites.

/**
 * What the song is about, as [answer] has it (#908), under the rule *Ask AI* draws: its sentences as
 * an insight's ([CheckedProse]'s look), a sentence citing a page it was not given taken out, nothing
 * said of it (#1437), an uncited one dimmed ([dimmedText], #954), the last of a run of them followed
 * by a `?` chip, read "(not cited)", that goes to the note under the text ([UnbackedNote], "Dimmed
 * text is not backed by any source."), offered as an action too, "Not cited, see the note".
 * Each citation is the line
 * *Sources* lists its page under (#1067: [lines], *Sources*' own, by the page's address, `sourcesLines`
 * naming it *explanation*), shown by that line's id ("S7") and opening *Sources* at it ([onCite]), as
 * an insight's citation does; a page *Sources* has no line for (no evidence to add it to) keeps the
 * id it was given and opens in the browser. A screen reader hears each citation as "Source S7", and
 * has each line as an action of the text too, as an insight's paragraph offers its lines.
 *
 * Also a genre's reason from *Ask AI* (#952), tagged [tag]: one from the model's own knowledge, as
 * its check allowed it ([app.sleevesight.core.synthesis.GroundedText.allowModel]), shows each `[AI]`
 * as an insight's **AI** chip, read "From the AI model’s own knowledge, not verified".
 */
@Composable
internal fun AiExplanation(
    answer: SearchAnswer.Explanation,
    lines: EvidenceSet? = null,
    onCite: (String) -> Unit = {},
    tag: String = LYRICS_EXPLANATION_TAG,
) {
    val uris = LocalUriHandler.current
    val resources = LocalResources.current
    val chips = citeStyles()
    val flagged = dimmedText()
    val byId = remember(answer, lines) { citedLines(answer.pages, lines) }
    val sentences = answer.grounded.paragraphs.flatten()
    val modelAllowed = answer.grounded.allowModel
    // A sentence citing a page it was not given is taken out (#1437).
    val runs =
        remember(answer, byId) {
            val ids = CitedIds(byId, modelAllowed)
            sentences.zip(shown(sentences.map { it.text }, checked = true)).filterNot { (sentence, parts) ->
                sentence.status == SentenceStatus.UNKNOWN ||
                    parts.any { it is ShownRun.Cite && byId[it.id] == null && !ids.model(it.id) }
            }
        }
    // The sentences that end with a `?` chip (#954): the last of each run of doubtful ones shown, the
    // text drawn as one paragraph; a sentence that shows as nothing neither ends a run nor breaks one.
    val marked = remember(runs) { chipEnds(runs) }
    val uncited = stringResource(R.string.home_cite_none)
    val spoken =
        runs.withIndex().joinToString(" ") { (i, run) ->
            spokenSentence(run.first, run.second, CitedIds(byId, modelAllowed), uncited.takeIf { marked[i] }, resources)
        }
    // A citation's line in *Sources*, or, with none, its page in the browser.
    val open = { cited: CitedLine -> if (cited.inSources) onCite(cited.shown) else openWebLink(uris, cited.url) }
    val dimmed = runs.any { (sentence, parts) -> doubtful(sentence) && !parts.isBlank() }
    WithUnbackedNote {
        val note = LocalNoteFocus.current?.takeIf { dimmed }
        val seeNote = stringResource(R.string.home_cite_see_note)
        val text =
            explanationText(runs, byId, ExplanationLooks(chips, flagged, note, marked, modelAllowed)) { id ->
                byId[id]?.let(open)
            }
        val exported = remember(text) { withoutIsolates(text) }
        Text(
            text,
            style = citedTextStyle(MaterialTheme.typography.bodyLarge),
            modifier =
                Modifier.testTag(tag).semantics {
                    this.text = exported
                    contentDescription = spoken
                    customActions =
                        byId.values.distinctBy { it.shown }.take(MAX_PAGE_ACTIONS).map { cited ->
                            CustomAccessibilityAction(resources.getString(R.string.home_cite, cited.shown)) {
                                open(cited)
                                true
                            }
                        } +
                        listOfNotNull(
                            note?.let { focus ->
                                CustomAccessibilityAction(seeNote) {
                                    focus.go()
                                    true
                                }
                            },
                        )
                },
        )
        // The note the dimmed sentences' `?` chips go to (#954), right under the text.
        if (dimmed) UnbackedNote(Unbacked(lines = emptyList(), uncited = true))
    }
}

// How the explanation's text looks: its citations' [chips], a doubtful sentence's look ([flagged]),
// the note its `?` chips go to ([note], #954), which sentences end with one ([marked]), and whether
// `[AI]` is an **AI** chip ([modelAllowed], a genre's reason from the model's knowledge, #952).
private class ExplanationLooks(
    val chips: CiteStyles,
    val flagged: SpanStyle,
    val note: NoteFocus?,
    val marked: List<Boolean>,
    val modelAllowed: Boolean = false,
)

// The pages cited, by the id each was given ([byId]), and whether `[AI]` is allowed ([modelAllowed]).
private class CitedIds(
    val byId: Map<String, CitedLine>,
    val modelAllowed: Boolean,
) {
    // Whether citing [id] is the model's own knowledge, allowed (#952).
    fun model(id: String): Boolean = modelAllowed && id == GroundingCheck.MODEL_ID
}

// Which of [runs] end with a `?` chip (#954, [runEnds]), among those that show.
private fun chipEnds(runs: List<Pair<CheckedSentence, List<ShownRun>>>): List<Boolean> {
    val shownAt = runs.indices.filterNot { runs[it].second.isBlank() }
    val ends = runEnds(shownAt.map { i -> UNCITED_RUN.takeIf { doubtful(runs[i].first) } })
    val marked = MutableList(runs.size) { false }
    shownAt.forEachIndexed { k, i -> marked[i] = ends[k] }
    return marked
}

/**
 * *Read more* (#1067), last under the explanation, after its credit and when it was written: "Read
 * more:" and the site ([siteOf]) of each page [answer]'s sentences cite, in the order first cited,
 * each address once (`sameAddress`), a link of its own that opens the page in the browser, " · "
 * between ([SitesLine]), in the small muted type *Sources* names a page's site in, with no mark of a
 * page that opens outside the app. A screen reader reads "Read more:", then each site as a link.
 */
@Composable
internal fun ReadMore(answer: SearchAnswer.Explanation) {
    val urls =
        answer.pages.fold(listOf<String>()) { kept, page ->
            if (kept.any { sameAddress(it, page.url) }) kept else kept + page.url
        }
    SitesLine(
        stringResource(R.string.home_lyrics_read_more),
        urls,
        MaterialTheme.typography.labelSmall,
        Modifier.testTag(LYRICS_READ_MORE_TAG),
    )
}

/**
 * A page the explanation cites, as its citations show it (#1067): the id of its line in *Sources*
 * ([shown], where [inSources]), else the id it was given, and its address ([url]).
 */
internal class CitedLine(
    val shown: String,
    val url: String,
    val inSources: Boolean,
)

/**
 * [pages], by the id each was given ("S1"), as their citations show them: the line [lines]
 * (*Sources*' own) lists each page's address under ([lineOf], #1067), else the page itself.
 */
internal fun citedLines(
    pages: List<CitedPage>,
    lines: EvidenceSet?,
): Map<String, CitedLine> =
    pages.associate { page ->
        val line = lineOf(page.url, lines)
        page.id to CitedLine(line ?: page.id, page.url, line != null)
    }

/**
 * The id of the line [lines] shows the page at [url] under (`sameAddress`), among any of its lines
 * (#1067: the song's explanation cites the track's own pages too, its Wikipedia article among them),
 * a web line first; null for none.
 */
internal fun lineOf(
    url: String,
    lines: EvidenceSet?,
): String? =
    webLineOf(url, lines)
        ?: lines?.lines?.firstOrNull { line -> line.url?.let { sameAddress(it, url) } == true }?.id

// Whether [sentence] is shown dimmed: it cites no page.
private fun doubtful(sentence: CheckedSentence): Boolean = sentence.status == SentenceStatus.UNCITED

// The sentences' [runs] as shown: a doubtful one dimmed ([ExplanationLooks.flagged]), the last of a
// run of them followed by a `?` chip (#954, [appendUnbacked]), each citation of a page in [byId] a
// chip as the insight's ([appendCite], in [ExplanationLooks.chips]) showing its line's id, whose tap
// runs [open] with the id the page was given, any other a missing one's.
private fun explanationText(
    runs: List<Pair<CheckedSentence, List<ShownRun>>>,
    byId: Map<String, CitedLine>,
    looks: ExplanationLooks,
    open: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        runs.forEachIndexed { i, (sentence, parts) ->
            if (i > 0) append(' ')
            if (looks.marked[i]) {
                val (before, after) = unbackedAt(parts)
                appendParts(before, byId, looks, looks.flagged, open)
                appendUnbacked(before, looks.chips, looks.note)
                appendParts(after, byId, looks, looks.flagged, open)
            } else {
                appendParts(parts, byId, looks, looks.flagged.takeIf { doubtful(sentence) }, open)
            }
        }
    }

// A sentence's [parts], its text in [flagged] when dimmed, its citations as [explanationText] says.
private fun AnnotatedString.Builder.appendParts(
    parts: List<ShownRun>,
    byId: Map<String, CitedLine>,
    looks: ExplanationLooks,
    flagged: SpanStyle?,
    open: (String) -> Unit,
) {
    for (part in parts) {
        when (part) {
            is ShownRun.Text -> appendText(part, flagged)
            is ShownRun.Cite -> {
                val cited = byId[part.id]
                if (cited == null && looks.modelAllowed && part.id == GroundingCheck.MODEL_ID) {
                    appendCite(part.id, part.id, CiteKind.MODEL, looks.chips, open)
                } else if (cited == null) {
                    appendCite(part.id, part.id, CiteKind.UNKNOWN, looks.chips, open)
                } else {
                    // Tagged by the line it opens, as an insight's chip is; tapped, by its own id.
                    appendCite(cited.shown, cited.shown, CiteKind.LINE, looks.chips, onCite = { open(part.id) })
                }
            }
        }
    }
}

// [sentence] ([parts]) as a screen reader hears it: each citation "Source S7", by its line in
// *Sources*, or that no such page was given; `[AI]`, where allowed, "From the AI model’s own
// knowledge, not verified" (#952); the last of a run of doubtful ones said to be not cited, as its `?`
// chip shows it ([uncited], "not cited", #1437; null for any other).
private fun spokenSentence(
    sentence: CheckedSentence,
    parts: List<ShownRun>,
    ids: CitedIds,
    uncited: String?,
    resources: Resources,
): String {
    val words =
        parts
            .joinToString("") { part ->
                when (part) {
                    is ShownRun.Text -> part.text
                    is ShownRun.Cite -> {
                        val cited = ids.byId[part.id]
                        val said =
                            when {
                                cited != null -> resources.getString(R.string.home_cite, cited.shown)
                                ids.model(part.id) -> resources.getString(R.string.home_cite_model)
                                else -> resources.getString(R.string.home_lyrics_cite_unknown, part.id)
                            }
                        " ($said) "
                    }
                }
            }.replace(SPACES, " ")
            .replace(SPACE_BEFORE_STOP, "$1")
            .trim()
    return if (doubtful(sentence) && uncited != null) notCited(words, uncited) else words
}

/**
 * The test tag of *Explain the song*'s (#908) part that *Ask AI*'s [base] tags (`AiSearchBlock`) (its button, its line
 * while it runs and its *Stop*, its rule, credit and lines), so it stands apart from *Find the
 * lyrics* in the same panel.
 */
fun explainTag(base: String): String = "$base:explain"

/** The test tags of the explanation's text, and of its *Read more* line (#1067). */
const val LYRICS_EXPLANATION_TAG: String = "lyrics-explanation"
const val LYRICS_READ_MORE_TAG: String = "lyrics-explanation:read-more"

private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The accessibility actions the text offers for its pages, below Compose's 32 a node may have.
private const val MAX_PAGE_ACTIONS = 16
