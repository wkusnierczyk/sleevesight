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
import app.sleevesight.core.synthesis.SearchKind

// What *Ask AI automatically* asked says so (#956), found or not: "; asked automatically." at the end
// of the credit or the nothing line, a link to the setting. In *Dates*, *Genres* and *Lyrics*, under
// a genre with no definition, and for the spelling check (#1293).

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

/**
 * A nothing line asked automatically (#956), [line] with [link] at its end; [words] its first
 * argument (the model, or the spelling check's title).
 */
@Composable
internal fun autoNothingParts(
    @StringRes line: Int,
    words: String,
    link: TextPart.Action,
): List<TextPart> = linkedParts(stringResource(line), TextPart.Words(words), link)

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

/**
 * The note on what *Ask AI automatically* asked (#956): its link opens the settings at it ([open]);
 * [web] is the nothing line from the web that ends with it.
 */
internal class AutoNote(
    val open: (SettingsTarget) -> Unit,
    @StringRes val web: Int,
)

/**
 * The note for [kind]'s answers asked automatically (#956), opening the settings as [search] does:
 * for every search *Ask AI automatically* may ask, the panels', a genre's definition and the spelling
 * check (#1293); none for *Explain the song*, never asked so.
 */
internal fun autoNote(
    search: PanelSearch,
    kind: SearchKind,
): AutoNote? =
    if (kind == SearchKind.EXPLAIN) null else AutoNote(search.openSettingsAt, SearchTexts.of(kind).nothingWebAuto)

/** The test tag of the note's link, as a screen reader finds it (#956). */
val AUTO_ASKED_LINK_TAG: String = SettingsTarget.searchWithAi.linkTag
