package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextStyle
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart

// The pages the AI's genres from the web rest on, under their row's sources in *Tag sources* (#1205).

/**
 * Under a row's sources (#1205): "From:" and the citation of each of [lines], the pages the AI's
 * genres rest on, spaces between, each a link to its line in *Sources* ([onCite]), as the *Described*
 * row's are ([citation]). Its row reads it ([fromReading]).
 */
@Composable
internal fun FromPages(
    lines: List<String>,
    style: TextStyle,
    onCite: (String) -> Unit,
) {
    val from = TextPart.Words(stringResource(R.string.home_genre_tag_sources_from))
    val parts = lines.fold(listOf<TextPart>(from)) { all, line -> all + TextPart.Words(" ") + citation(line, onCite) }
    LinkedText(
        parts,
        style = style,
        modifier = Modifier.testTag(GENRE_SOURCES_FROM_TAG),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * How a screen reader reads the row [name] of sources, its words [sources], with the citations
 * [cites] (#1205), as each reads ("Source S7"): "Track: Claude Haiku 5.5, AI. From: Source S7,
 * Source S8"; null for a row with none, which reads as its words do.
 */
@Composable
internal fun fromReading(
    name: String,
    sources: String,
    cites: List<String>,
): String? {
    if (cites.isEmpty()) return null
    return stringResource(R.string.home_genre_tag_sources_reading, name, sources, cites.joinToString(", "))
}

/**
 * A row of sources read as [reading], each of [cites] (a line and how it reads) an action that opens
 * the line ([onCite]).
 */
internal fun SemanticsPropertyReceiver.citedRow(
    reading: String,
    cites: List<Pair<String, String>>,
    onCite: (String) -> Unit,
) {
    contentDescription = reading
    customActions =
        cites.map { (line, label) ->
            CustomAccessibilityAction(label) {
                onCite(line)
                true
            }
        }
}

/**
 * The test tag of the line of citations under the sources of the row the AI's genres from the web
 * joined (#1205).
 */
const val GENRE_SOURCES_FROM_TAG: String = "genre-sources:from"
