package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration

// *AI services* → *Retry when the AI fails* (#1164), the section's last row.

/** The test tag of *Retry when the AI fails*'s slider. */
internal const val AI_RETRY_SLIDER_TAG = "settings-ai-retry-slider"

/** The test tag of the labels under *Retry when the AI fails*'s stops. */
internal const val AI_RETRY_STOPS_TAG = "settings-ai-retry-stops"

/** The test tag of *Retry when the AI fails*'s value, at the row's right. */
internal const val AI_RETRY_VALUE_TAG = "settings-ai-retry-value"

/**
 * *Retry when the AI fails* (`Settings.aiRetry`, #1164), after the AI services, since it applies to
 * whichever is on: how long an AI call that fails as busy, with its connection failed, or with its
 * reply cut short is asked again before its error is shown. A stepped slider ([SteppedRow]) as
 * *Wait when busy*'s, one stop for each of [Settings.AI_RETRIES], its description naming the value
 * chosen ([aiRetrySummary]). With no AI service on ([noAiServiceOn]), the whole row dims and cannot
 * be moved (#999).
 */
@Composable
internal fun AiRetryRow(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
) {
    val title = stringResource(R.string.settings_ai_retry)
    if (!shownInSearch(title)) return
    val none = stringResource(R.string.settings_ai_retry_none)
    SteppedRow(
        title = title,
        summary = { shown, label -> aiRetrySummary(shown, label) },
        stops = Settings.AI_RETRIES,
        value = settings.aiRetry,
        none = none,
        applies = !noAiServiceOn(settings, secrets),
        tags = SteppedTags(AI_RETRY_SLIDER_TAG, AI_RETRY_STOPS_TAG, AI_RETRY_VALUE_TAG),
        choose = { retry -> update { it.copy(aiRetry = retry) } },
    )
}

/**
 * The row's description, naming the value chosen ([label], as under its stop: "for up to 30 s");
 * with *Don’t retry* ([retry] zero), that the error shows at once.
 */
@Composable
private fun aiRetrySummary(
    retry: Duration,
    label: String,
): String =
    if (retry == Duration.ZERO) {
        stringResource(R.string.settings_ai_retry_summary_none)
    } else {
        stringResource(R.string.settings_ai_retry_summary, label)
    }

/**
 * *Retry when the AI fails* (#1164) as the settings' search finds it: its name, its description as
 * shown for the value chosen in [settings], and the stops' labels.
 */
@Composable
internal fun aiRetryIndexRow(settings: Settings): IndexedRow {
    val title = stringResource(R.string.settings_ai_retry)
    val none = stringResource(R.string.settings_ai_retry_none)
    val labels = Settings.AI_RETRIES.map { stopLabel(it, none) }
    val summary = aiRetrySummary(settings.aiRetry, stopLabel(settings.aiRetry, none))
    return IndexedRow(title, listOf(title, summary) + labels)
}
