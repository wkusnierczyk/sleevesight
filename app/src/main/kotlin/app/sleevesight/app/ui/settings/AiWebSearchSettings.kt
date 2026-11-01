package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ActionButtonGroup
import app.sleevesight.app.ui.theme.GroupAction
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings

// *Insights* → *AI web search* (#801) and its *Web searches at most*, after *Ask AI automatically*.

/**
 * *AI web search* (`aiWebSearch`, #801): *Off*, *Once* or *Up to {n} times*, a button group kept to
 * one row at any font size ([ChoiceButtonsRow]'s `oneRow`), what the chosen value does under it,
 * and, while *Up to {n} times* is chosen, *Web searches at most* ([LimitRow]). It needs Exa web
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
    val limit = settings.aiWebSearchLimit
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
    if (exa && settings.aiWebSearch == AiWebSearch.UP_TO) LimitRow(limit, settings.writeInsights, update)
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
 * *Web searches at most* (`aiWebSearchLimit`, #801): its name and the number, read together ("Web
 * searches at most, 3"), and a stepper of two buttons, *Decrease* and *Increase*, from 2 to 5
 * (`Settings.AI_WEB_SEARCH_LIMITS`), each disabled at its end, and both while [enabled] is false.
 */
@Composable
private fun LimitRow(
    limit: Int,
    enabled: Boolean,
    update: Update,
) {
    val title = stringResource(R.string.settings_ai_web_search_limit)
    if (!shownInSearch(title)) return
    val range = Settings.AI_WEB_SEARCH_LIMITS
    val decrease = stringResource(R.string.settings_stepper_decrease)
    val increase = stringResource(R.string.settings_stepper_increase)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            searchMarked(title),
            style = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(ROW_TITLE_TAG)
                    .clearAndSetSemantics { contentDescription = "$title, $limit" },
        )
        Text(
            limit.toString(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 12.dp).testTag(LIMIT_VALUE_TAG).clearAndSetSemantics {},
        )
        Box(Modifier.width(STEPPER_WIDTH)) {
            ActionButtonGroup(
                listOf(
                    GroupAction(
                        "−",
                        enabled && limit > range.first,
                        Modifier.semantics { contentDescription = decrease }.testTag(LIMIT_DECREASE_TAG),
                    ) { update { it.copy(aiWebSearchLimit = (limit - 1).coerceIn(range)) } },
                    GroupAction(
                        "+",
                        enabled && limit < range.last,
                        Modifier.semantics { contentDescription = increase }.testTag(LIMIT_INCREASE_TAG),
                    ) { update { it.copy(aiWebSearchLimit = (limit + 1).coerceIn(range)) } },
                ),
            )
        }
    }
}

/**
 * *AI web search* (#801) as the search finds it: its name, description, value and choices with what
 * each does, the lines while Exa web search is off; and *Web searches at most* while it shows.
 */
@Composable
internal fun aiWebSearchIndexRows(settings: Settings): List<IndexedRow> {
    val limit = settings.aiWebSearchLimit
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
    val shown = settings.aiWebSearchOn && settings.aiWebSearch == AiWebSearch.UP_TO
    val limitRow = indexRow(stringResource(R.string.settings_ai_web_search_limit), limit.toString()).takeIf { shown }
    return listOfNotNull(web, limitRow)
}

/** The stepper's width: two buttons as wide as a chip of one short label each. */
private val STEPPER_WIDTH = 112.dp

/** The test tags of *Web searches at most*: the number, and the stepper's buttons. */
internal const val LIMIT_VALUE_TAG: String = "ai-web-search-limit:value"
internal const val LIMIT_DECREASE_TAG: String = "ai-web-search-limit:decrease"
internal const val LIMIT_INCREASE_TAG: String = "ai-web-search-limit:increase"
