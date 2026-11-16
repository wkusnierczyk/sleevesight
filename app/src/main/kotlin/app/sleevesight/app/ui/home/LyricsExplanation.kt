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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
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
 * sentences cite, its id as the text's chip of it, then its site as a link that opens in the browser,
 * in the small type *Sources* sets a page's site in (#1344). A screen
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
    val columns = citedColumns(answer.pages.map { it.id }, chips.line)
    Column(verticalArrangement = Arrangement.spacedBy(CITED_ROW_GAP)) {
        for (page in answer.pages) CitedPageLink(page, columns, chips.line)
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
// opens outside the app to sit on a link's first line, as large as their text ([markSize], #1344).
private class CitedColumns(
    val idWidth: Dp,
    val lineHeight: Dp,
    val lineBaseline: Int,
    val markSize: Dp,
) {
    companion object {
        // The cited page [id] as the explanation's text shows its chip ([appendCite]'s look of a page
        // it was given, [chip]), at the size of the text it is set in, the sites' (#1344), not the
        // text's fraction of it. Its padding is no-break spaces: a line's trailing spaces would hang
        // past its end when right-aligned, and the chip lose its right side.
        fun idChip(
            id: String,
            chip: SpanStyle,
        ): AnnotatedString =
            buildAnnotatedString {
                withStyle(chip.copy(fontSize = TextUnit.Unspecified)) {
                    append("$NO_BREAK_SPACE$id$NO_BREAK_SPACE")
                }
            }
    }
}

// [CitedColumns] for the ids [ids], as [CitedPageLink] draws them (as chips in [chip]) and its links,
// at the font size the system has.
@Composable
private fun citedColumns(
    ids: List<String>,
    chip: SpanStyle,
): CitedColumns {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall
    return remember(ids, chip, style, measurer, density) {
        fun measured(text: AnnotatedString) = measurer.measure(text, style, maxLines = 1, softWrap = false)
        val widest = ids.maxOfOrNull { measured(CitedColumns.idChip(it, chip)).size.width } ?: 0
        val line = measured(AnnotatedString("S"))
        with(density) {
            CitedColumns(widest.toDp(), line.size.height.toDp(), line.firstBaseline.roundToInt(), style.fontSize.toDp())
        }
    }
}

// A page the explanation cites: its id, as the text's chip of it ([CitedColumns.idChip], in [chip]),
// then its site ([siteOf]: its host, as *Sources* names a page, #1040; not the title the page gives
// itself), as a link with the mark of a page that opens outside the app; both in the small type
// *Sources* sets a page's site in (#1344). Two columns (#1287): the ids aligned to their column's
// end, as wide as the widest of [columns], the links to their column's start (its left, or its right
// in a right-to-left layout), a site breaking only after a dot; the id and the mark on the link's
// first line, by its baseline, the mark right after that line's text, when the link wraps.
//
// A row is as high as its text, not the 48 dp of a touch target, so the rows sit close (#1344). A
// touch goes to the nearer link: its own row and half the gap on either side. Compose widens a tap
// target smaller than 48 dp into the room around it, and splits the room two widened targets share
// at its middle; so only above the first row and below the last does a link's reach come to 48 dp.
// A stylus or a mouse, which Compose does not widen for, hits the link's text itself.
@Composable
private fun CitedPageLink(
    page: CitedPage,
    columns: CitedColumns,
    chip: SpanStyle,
) {
    // How far the link's first line ends before the link does, at its end side (a site that wraps,
    // or one narrower than the link's least width), for the mark to follow the text; read when
    // placing it.
    var short by remember { mutableIntStateOf(0) }
    // A site's name takes its own text's direction (mostly left to right), which would set it at
    // the left in a right-to-left layout; it is set at its column's start, by the layout's direction.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val site = MaterialTheme.typography.labelSmall.copy(textAlign = if (rtl) TextAlign.Right else TextAlign.Left)
    Row(
        modifier = Modifier.testTag(lyricsCitedPageTag(page.id)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            CitedColumns.idChip(page.id, chip),
            style = MaterialTheme.typography.labelSmall,
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
        // No least size laid out: the link is as high as its text, its tap target widened by Compose
        // instead.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            LinkLabel(
                TextPart.Link(siteOf(page.url), page.url),
                onClickLabel = stringResource(R.string.link_open),
                style = site,
                // At least the touch target's width, so a short site ("x.com") is as wide a target
                // laid out as Compose widens it to, and starts at the column's start.
                modifier = Modifier.alignByBaseline().weight(1f, fill = false).widthIn(min = LINK_MIN_WIDTH),
                breakAfterDots = true,
                onTextLayout = {
                    val room = if (rtl) it.getLineLeft(0) else it.size.width - it.getLineRight(0)
                    short = room.roundToInt().coerceAtLeast(0)
                },
            )
        }
        // A box a line of the link high, its baseline where the link's first line has its own; moved
        // back by [short] toward the start (`offset` mirrors right to left).
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
                modifier = Modifier.size(columns.markSize),
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

/** The space between two cited pages' rows (#1344). */
internal val CITED_ROW_GAP = 8.dp
private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The accessibility actions the text offers for its pages, below Compose's 32 a node may have.
private const val MAX_PAGE_ACTIONS = 16
