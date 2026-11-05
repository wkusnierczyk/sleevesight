package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SentenceStatus

// *Explain the song*'s answer in the *Lyrics* panel (#908): its sentences, cited as an insight's are,
// then the pages they cite.

/**
 * What the song is about, as [answer] has it (#908), under the rule *Ask AI* draws: its sentences as
 * an insight's ([CheckedProse]'s look), each citation a chip that opens its page in the browser, a
 * sentence no page backs (uncited, or citing a page it was not given) dimmed, in the muted colour and
 * in italics, and read as not cited; then "Pages cited" in the quiet note style, and each page its
 * sentences cite, its id, then its title (or its site) as a link that opens in the browser. A screen
 * reader hears each citation as "Source S1", and has each page as an action of the text too, as an
 * insight's paragraph offers its lines.
 */
@Composable
internal fun AiExplanation(answer: SearchAnswer.Explanation) {
    val uris = LocalUriHandler.current
    val resources = LocalResources.current
    val colours = MaterialTheme.colorScheme
    val styles =
        ExplainStyles(
            chip = chip(colours.secondaryContainer, colours.onSecondaryContainer),
            missing = chip(colours.errorContainer, colours.onErrorContainer),
            flagged = SpanStyle(color = colours.onSurfaceVariant, fontStyle = FontStyle.Italic),
        )
    val byId = remember(answer) { answer.pages.associateBy { it.id } }
    val sentences = answer.grounded.paragraphs.flatten()
    val runs = remember(answer) { sentences.zip(shown(sentences.map { it.text })) }
    val uncited = stringResource(R.string.home_cite_none)
    val spoken =
        runs.joinToString(
            " ",
        ) { (sentence, parts) -> spokenSentence(sentence, parts, byId, uncited, resources) }
    Text(
        explanationText(runs, byId, styles) { open(uris, it) },
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.testTag(LYRICS_EXPLANATION_TAG).semantics {
                contentDescription = spoken
                customActions =
                    answer.pages.take(MAX_PAGE_ACTIONS).map { page ->
                        CustomAccessibilityAction(resources.getString(R.string.home_cite, page.id)) {
                            open(uris, page.url)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (page in answer.pages) CitedPageLink(page)
    }
}

// How the explanation's text is set: a citation of a page given, one of none, a doubtful sentence.
private class ExplainStyles(
    val chip: SpanStyle,
    val missing: SpanStyle,
    val flagged: SpanStyle,
)

// A citation's chip, as an insight's are.
private fun chip(
    background: Color,
    foreground: Color,
) = SpanStyle(color = foreground, fontSize = CITE_SIZE, fontWeight = FontWeight.Medium, background = background)

// Whether [sentence] is shown dimmed: no page backs it.
private fun doubtful(sentence: CheckedSentence): Boolean =
    sentence.status == SentenceStatus.UNCITED || sentence.status == SentenceStatus.UNKNOWN

// The sentences' [runs] as shown: a doubtful one dimmed, each citation of a page in [byId] a chip
// that opens it ([open]), any other a chip in the error colours.
private fun explanationText(
    runs: List<Pair<CheckedSentence, List<ShownRun>>>,
    byId: Map<String, CitedPage>,
    styles: ExplainStyles,
    open: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        runs.forEachIndexed { i, (sentence, parts) ->
            if (i > 0) append(' ')
            for (part in parts) {
                when (part) {
                    is ShownRun.Text -> {
                        val marked =
                            SpanStyle(
                                fontStyle = if (part.italic) FontStyle.Italic else null,
                                fontWeight = if (part.bold) FontWeight.Bold else null,
                            )
                        withStyle(
                            if (doubtful(sentence)) styles.flagged.merge(marked) else marked,
                        ) { append(part.text) }
                    }
                    is ShownRun.Cite -> {
                        val page = byId[part.id]
                        if (page == null) {
                            withStyle(styles.missing) { append(" ${part.id} ") }
                        } else {
                            withLink(LinkAnnotation.Url(page.url, TextLinkStyles(styles.chip)) { open(page.url) }) {
                                append(" ${part.id} ")
                            }
                        }
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

// A page the explanation cites: its id, then its title, or else its site, as a link with the mark of
// a page that opens outside the app.
@Composable
private fun CitedPageLink(page: CitedPage) {
    Row(
        modifier = Modifier.testTag(lyricsCitedPageTag(page.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            page.id,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = ID_WIDTH),
        )
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LinkLabel(
                TextPart.Link(page.title?.takeIf(String::isNotBlank) ?: siteOf(page.url), page.url),
                onClickLabel = stringResource(R.string.link_open),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// Opens [url] in the browser; with no app that opens web pages, nothing (its address is listed).
private fun open(
    uris: UriHandler,
    url: String,
) {
    try {
        uris.openUri(url)
    } catch (_: IllegalArgumentException) {
        // No app opens web pages.
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

private val CITE_SIZE = 0.8.em
private val ID_WIDTH = 28.dp
private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The accessibility actions the text offers for its pages, below Compose's 32 a node may have.
private const val MAX_PAGE_ACTIONS = 16
