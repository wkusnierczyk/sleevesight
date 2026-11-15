package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.openWebLink
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SentenceStatus
import kotlin.math.roundToInt

// *Explain the song*'s answer in the *Lyrics* panel (#908): its sentences, cited as an insight's are,
// then the pages they cite.

/**
 * What the song is about, as [answer] has it (#908), under the rule *Ask AI* draws: its sentences as
 * an insight's ([CheckedProse]'s look), each citation a chip that opens its page in the browser, a
 * sentence no page backs (uncited, or citing a page it was not given) dimmed, in the muted colour and
 * in italics, and read as not cited; then "Pages cited" in the quiet note style, and each page its
 * sentences cite, its id, then its site as a link that opens in the browser. A screen
 * reader hears each citation as "Source S1", and has each page as an action of the text too, as an
 * insight's paragraph offers its lines.
 */
@Composable
internal fun AiExplanation(answer: SearchAnswer.Explanation) {
    val uris = LocalUriHandler.current
    val resources = LocalResources.current
    val colours = MaterialTheme.colorScheme
    val chips = citeStyles()
    val flagged = SpanStyle(color = colours.onSurfaceVariant, fontStyle = FontStyle.Italic)
    val byId = remember(answer) { answer.pages.associateBy { it.id } }
    val sentences = answer.grounded.paragraphs.flatten()
    val runs = remember(answer) { sentences.zip(shown(sentences.map { it.text }, checked = true)) }
    val uncited = stringResource(R.string.home_cite_none)
    val spoken =
        runs.joinToString(
            " ",
        ) { (sentence, parts) -> spokenSentence(sentence, parts, byId, uncited, resources) }
    Text(
        explanationText(runs, byId, chips, flagged) { id -> byId[id]?.let { openWebLink(uris, it.url) } },
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.testTag(LYRICS_EXPLANATION_TAG).semantics {
                contentDescription = spoken
                customActions =
                    answer.pages.take(MAX_PAGE_ACTIONS).map { page ->
                        CustomAccessibilityAction(resources.getString(R.string.home_cite, page.id)) {
                            openWebLink(uris, page.url)
                            true
                        }
                    }
            },
    )
    Text(
        stringResource(R.string.home_lyrics_pages_cited),
        style = MaterialTheme.typography.bodySmall,
        color = colours.onSurfaceVariant,
        modifier = Modifier.testTag(LYRICS_PAGES_CITED_TAG),
    )
    val columns = citedColumns(answer.pages.map { it.id })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (page in answer.pages) CitedPageLink(page, columns)
    }
}

// Whether [sentence] is shown dimmed: no page backs it.
private fun doubtful(sentence: CheckedSentence): Boolean =
    sentence.status == SentenceStatus.UNCITED || sentence.status == SentenceStatus.UNKNOWN

// The sentences' [runs] as shown: a doubtful one dimmed ([flagged]), each citation of a page in
// [byId] a chip as the insight's ([appendCite], in [chips]) whose tap runs [open] with its id,
// any other a missing one's.
private fun explanationText(
    runs: List<Pair<CheckedSentence, List<ShownRun>>>,
    byId: Map<String, CitedPage>,
    chips: CiteStyles,
    flagged: SpanStyle,
    open: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        runs.forEachIndexed { i, (sentence, parts) ->
            if (i > 0) append(' ')
            for (part in parts) {
                when (part) {
                    is ShownRun.Text -> appendText(part, flagged.takeIf { doubtful(sentence) })
                    is ShownRun.Cite -> {
                        val kind = if (part.id in byId) CiteKind.LINE else CiteKind.UNKNOWN
                        appendCite(part.id, part.id, kind, chips, open)
                    }
                }
            }
        }
    }

// [sentence] ([parts]) as a screen reader hears it: each citation "Source S1", or that no such page
// was given; a doubtful one said to be not cited ([uncited]).
private fun spokenSentence(
    sentence: CheckedSentence,
    parts: List<ShownRun>,
    byId: Map<String, CitedPage>,
    uncited: String,
    resources: Resources,
): String {
    val words =
        parts
            .joinToString("") { part ->
                when (part) {
                    is ShownRun.Text -> part.text
                    is ShownRun.Cite -> {
                        val said = if (byId[part.id] != null) R.string.home_cite else R.string.home_lyrics_cite_unknown
                        " (${resources.getString(said, part.id)}) "
                    }
                }
            }.replace(SPACES, " ")
            .replace(SPACE_BEFORE_STOP, "$1")
            .trim()
    return if (doubtful(sentence)) "$words ($uncited)" else words
}

// How the cited pages line up (#1287): the ids' column as wide as the widest [idWidth], and the
// height and first baseline of a line of the links ([lineHeight], [lineBaseline]), for the mark that
// opens outside the app to sit on a link's first line.
private class CitedColumns(
    val idWidth: Dp,
    val lineHeight: Dp,
    val lineBaseline: Int,
)

// [CitedColumns] for the ids [ids], as [CitedPageLink] draws them and its links, at the font size the
// system has.
@Composable
private fun citedColumns(ids: List<String>): CitedColumns {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val idStyle = MaterialTheme.typography.labelMedium
    val linkStyle = MaterialTheme.typography.bodyLarge
    return remember(ids, idStyle, linkStyle, measurer, density) {
        val widest = ids.maxOfOrNull { measurer.measure(it, idStyle, maxLines = 1, softWrap = false).size.width } ?: 0
        val line = measurer.measure("S", linkStyle, maxLines = 1, softWrap = false)
        with(density) { CitedColumns(widest.toDp(), line.size.height.toDp(), line.firstBaseline.roundToInt()) }
    }
}

// A page the explanation cites: its id, then its site ([siteOf]: its host, as *Sources* names a page,
// #1040; not the title the page gives itself), as a link with the mark of a page that opens outside
// the app. Two columns (#1287): the ids right-aligned in theirs, as wide as the widest of [columns],
// the links left-aligned in theirs, a site breaking only after a dot; the id and the mark on the
// link's first line, by its baseline, the mark right after that line's text, when the link wraps.
@Composable
private fun CitedPageLink(
    page: CitedPage,
    columns: CitedColumns,
) {
    // How far the link's first line ends before the link does (a site that wraps, or one narrower
    // than the link's touch target), for the mark to follow the text; read when placing it.
    var short by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier.testTag(lyricsCitedPageTag(page.id)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            page.id,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier =
                Modifier
                    .alignByBaseline()
                    .padding(end = 4.dp)
                    .width(columns.idWidth)
                    .testTag(lyricsCitedPageIdTag(page.id)),
        )
        LinkLabel(
            TextPart.Link(siteOf(page.url), page.url),
            onClickLabel = stringResource(R.string.link_open),
            style = MaterialTheme.typography.bodyLarge,
            // At least the touch target's width itself, so a short site ("x.com") starts at the
            // column's left edge rather than centred in it.
            modifier = Modifier.alignByBaseline().weight(1f, fill = false).widthIn(min = LINK_MIN_WIDTH),
            breakAfterDots = true,
            onTextLayout = { short = (it.size.width - it.getLineRight(0).roundToInt()).coerceAtLeast(0) },
        )
        // A box a line of the link high, its baseline where the link's first line has its own.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .alignBy { columns.lineBaseline }
                    .offset { IntOffset(-short, 0) }
                    .height(columns.lineHeight)
                    .testTag(lyricsCitedPageMarkTag(page.id)),
        ) {
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The test tag of *Explain the song*'s (#908) part that *Ask AI*'s [base] tags (`AiSearchBlock`) (its button, its line
 * while it runs and its *Stop*, its rule, credit and lines), so it stands apart from *Find the
 * lyrics* in the same panel.
 */
fun explainTag(base: String): String = "$base:explain"

/** The test tags of the explanation's text, its "Pages cited" line, and each page it cites. */
const val LYRICS_EXPLANATION_TAG: String = "lyrics-explanation"
const val LYRICS_PAGES_CITED_TAG: String = "lyrics-explanation:pages"

/** The test tag of the cited page with [id] ("S1") under the explanation. */
fun lyricsCitedPageTag(id: String): String = "lyrics-explanation:page:$id"

/** The test tag of the id ("S1") of the cited page with [id]. */
fun lyricsCitedPageIdTag(id: String): String = "${lyricsCitedPageTag(id)}:id"

/** The test tag of the mark of a page that opens outside the app, after the cited page with [id]. */
fun lyricsCitedPageMarkTag(id: String): String = "${lyricsCitedPageTag(id)}:mark"

// The minimum touch target's width, which a link takes itself (#1287).
private val LINK_MIN_WIDTH = 48.dp
private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The accessibility actions the text offers for its pages, below Compose's 32 a node may have.
private const val MAX_PAGE_ACTIONS = 16
