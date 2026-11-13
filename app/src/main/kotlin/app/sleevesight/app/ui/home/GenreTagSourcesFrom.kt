package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextStyle
import app.sleevesight.app.R
import app.sleevesight.app.ui.openWebLink
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart

// The pages the AI's genres from the web rest on, under their row's sources in *Tag sources* (#1205).

/**
 * Under a row's sources (#1205): "From:" and the citation of each of [lines], the pages the AI's
 * genres rest on, spaces between, each a link to its line in *Sources* ([onCite]), as the *Described*
 * row's are ([citation]). Its row reads it ([citedRow]).
 */
@Composable
internal fun FromPages(
    lines: List<String>,
    style: TextStyle,
    onCite: (String) -> Unit,
) {
    LinkedText(
        fromParts(stringResource(R.string.home_genre_tag_sources_from), lines, onCite),
        style = style,
        modifier = Modifier.testTag(GENRE_SOURCES_FROM_TAG),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** [from] ("From:"), then the citation of each of [lines], a space before each ([citation]). */
internal fun fromParts(
    from: String,
    lines: List<String>,
    onCite: (String) -> Unit,
): List<TextPart> =
    lines.fold(
        listOf<TextPart>(TextPart.Words(from)),
    ) { all, line -> all + TextPart.Words(" ") + citation(line, onCite) }

/**
 * How a row of sources with the citations of [cited], the pages the AI's genres rest on (#1205), is
 * one item to a screen reader, in place of its parts (cleared), so nothing in it is read twice: read
 * as the row [name], its sources' [words], then each citation as it reads ("Track: Claude Haiku
 * 5.5, AI. From: Source S7, Source S8"), each citation an action that opens its line ([onCite]), then
 * each of [parts]' links one that opens its page in the browser ([openWebLink], #1040). No change to
 * a row with none, which reads as its words do.
 */
@Composable
internal fun citedRow(
    name: String,
    words: String,
    parts: List<TextPart>,
    cited: List<String>,
    onCite: (String) -> Unit,
): Modifier {
    if (cited.isEmpty()) return Modifier
    val uris = LocalUriHandler.current
    val cites = cited.map { line -> stringResource(R.string.home_cite, line) to { onCite(line) } }
    val links =
        parts.filterIsInstance<TextPart.Link>().map { link ->
            stringResource(R.string.home_genre_tag_sources_open, link.text) to { openWebLink(uris, link.url) }
        }
    val reading = stringResource(R.string.home_genre_tag_sources_reading, name, words, cites.joinToString { it.first })
    return Modifier.clearAndSetSemantics {
        contentDescription = reading
        customActions =
            (cites + links).map { (label, does) ->
                CustomAccessibilityAction(label) {
                    does()
                    true
                }
            }
    }
}

/**
 * The test tag of the line of citations under the sources of the row the AI's genres from the web
 * joined (#1205).
 */
const val GENRE_SOURCES_FROM_TAG: String = "genre-sources:from"
