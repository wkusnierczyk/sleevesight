package app.sleevesight.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings

// *Insights* → *AI web search* (#801), before *Ask AI automatically*.

/**
 * *AI web search* (`aiWebSearch`, #801): *Off*, *Once* or *Up to {n} times*, a button group kept to
 * one row at any font size ([ChoiceButtonsRow]'s `oneRow`), and what the chosen value does under
 * it; *Up to {n} times* searches at most `Settings.AI_WEB_SEARCH_LIMIT` times, fixed and said in
 * its text, with no setting of its own. It needs Exa web
 * search on (`Settings.aiWebSearchOn`): while it is off, *Off* shows chosen and the others cannot be
 * chosen, the choice saved kept for when it is on again, and two lines under the group say so, the
 * second a link to *Settings → Sources*. A paid call, as *Ask AI automatically* is: not to be
 * changed while *Show insights* is off.
 */
@Composable
internal fun AiWebSearchRows(
    settings: Settings,
    update: Update,
) {
    val exa = settings.aiWebSearchOn
    val limit = Settings.AI_WEB_SEARCH_LIMIT
    val choices =
        listOf(
            AiWebSearch.OFF to stringResource(R.string.settings_ai_web_search_off),
            AiWebSearch.ONCE to stringResource(R.string.settings_ai_web_search_once),
            AiWebSearch.UP_TO to pluralStringResource(R.plurals.settings_ai_web_search_up_to, limit, limit),
        )
    val details =
        mapOf(
            AiWebSearch.OFF to stringResource(R.string.settings_ai_web_search_off_detail),
            AiWebSearch.ONCE to stringResource(R.string.settings_ai_web_search_once_detail),
            AiWebSearch.UP_TO to
                pluralStringResource(R.plurals.settings_ai_web_search_up_to_detail, limit, limit, limit + 1),
        )
    LandingSpot(SettingsTarget.aiWebSearch) {
        ChoiceButtonsRow(
            title = stringResource(R.string.settings_ai_web_search),
            summary = stringResource(R.string.settings_ai_web_search_summary),
            choices = choices,
            selected = settings.aiWebSearchUsed,
            details = details,
            enabled = settings.writeInsights,
            disabled = if (exa) emptySet() else setOf(AiWebSearch.ONCE, AiWebSearch.UP_TO),
            oneRow = true,
            below = if (exa) null else ({ ExaOffLines() }),
        ) { chosen ->
            // While Exa is off only *Off* can be tapped, already shown chosen: the choice saved stays.
            if (exa) update { it.copy(aiWebSearch = chosen) }
        }
    }
}

/** Under the group while Exa web search is off: that it is, and a link to *Settings → Sources*. */
@Composable
private fun ExaOffLines() {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(R.string.settings_ai_web_search_exa_off), style = style, color = color)
    LinkedText(
        linkedParts(
            stringResource(R.string.settings_ai_web_search_exa_see),
            settingsLink(stringResource(R.string.settings_sources_link), SettingsTarget.sources),
        ),
        style = style,
        color = color,
    )
}

/**
 * *AI web search* (#801) as the search finds it: its name, description, value and choices with what
 * each does, and the lines while Exa web search is off.
 */
@Composable
internal fun aiWebSearchIndexRows(settings: Settings): List<IndexedRow> {
    val limit = Settings.AI_WEB_SEARCH_LIMIT
    val choices =
        listOf(
            stringResource(R.string.settings_ai_web_search_off),
            stringResource(R.string.settings_ai_web_search_off_detail),
            stringResource(R.string.settings_ai_web_search_once),
            stringResource(R.string.settings_ai_web_search_once_detail),
            pluralStringResource(R.plurals.settings_ai_web_search_up_to, limit, limit),
            pluralStringResource(R.plurals.settings_ai_web_search_up_to_detail, limit, limit, limit + 1),
        )
    val off =
        listOf(
            stringResource(R.string.settings_ai_web_search_exa_off),
            stringResource(R.string.settings_ai_web_search_exa_see, stringResource(R.string.settings_sources_link)),
        ).takeIf { !settings.aiWebSearchOn }.orEmpty()
    val web =
        IndexedRow(
            stringResource(R.string.settings_ai_web_search),
            listOf(
                stringResource(R.string.settings_ai_web_search),
                stringResource(R.string.settings_ai_web_search_summary),
            ) +
                choices + off,
        )
    return listOf(web)
}
