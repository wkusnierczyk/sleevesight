package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.NamedLinkText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts

// What *Ask AI automatically* asked in *Dates*, *Genres* and *Lyrics* says so (#956), found or not:
// "; asked automatically." at the end of the credit or the nothing line, a link to the setting.

/**
 * The note's link, "asked automatically", which opens the settings at *Ask AI automatically*
 * ([openSettingsAt], [SettingsTarget.searchWithAi]); [AutoAskedLine] sets it in its line.
 */
@Composable
internal fun autoAskedLink(openSettingsAt: (SettingsTarget) -> Unit): TextPart.Action =
    TextPart.Action(
        stringResource(R.string.home_ai_auto_link),
        SettingsTarget.searchWithAi.linkTag,
    ) { openSettingsAt(SettingsTarget.searchWithAi) }

/**
 * The credit of an answer asked automatically (#956), [credit]'s words with [link] at its end:
 * "Written by {model}; asked automatically.", with the credit's "with" list when it has one
 * ("Written by {model} with medium effort; asked automatically.").
 */
@Composable
internal fun autoCreditParts(
    credit: Credit,
    link: TextPart.Action,
): List<TextPart> {
    val resources = LocalResources.current
    val model = TextPart.Words(credit.model)
    return when (val with = creditWith(credit, resources)) {
        null -> linkedParts(resources.getString(R.string.home_credit_written_auto), model, link)
        else ->
            linkedParts(
                resources.getString(R.string.home_credit_written_with_auto),
                model,
                TextPart.Words(with),
                link,
            )
    }
}

/** A nothing line asked automatically (#956), [line] (its model [model]) with [link] at its end. */
@Composable
internal fun autoNothingParts(
    @StringRes line: Int,
    model: String,
    link: TextPart.Action,
): List<TextPart> = linkedParts(stringResource(line), TextPart.Words(model), link)

/**
 * [parts], a line with one link in it ([autoAskedLink]), in the quiet style of the notes under an
 * answer, in italics for the credit ([italic]); [tag] on it. A screen reader reads the line whole,
 * then the link, named with where it goes: "open the Ask AI automatically setting" (#956's pick,
 * [NamedLinkText]).
 */
@Composable
internal fun AutoAskedLine(
    parts: List<TextPart>,
    italic: Boolean,
    tag: String,
) {
    val style = MaterialTheme.typography.bodySmall
    NamedLinkText(
        parts,
        stringResource(R.string.home_ai_auto_link_action),
        if (italic) style.copy(fontStyle = FontStyle.Italic) else style,
        tag,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The test tag of the note's link, as a screen reader finds it (#956). */
val AUTO_ASKED_LINK_TAG: String = SettingsTarget.searchWithAi.linkTag
