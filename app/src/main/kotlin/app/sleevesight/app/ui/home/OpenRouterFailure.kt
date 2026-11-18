package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.model.ProviderId

// Home, when OpenRouter refuses later (#1157): the write failed, with what to do and a link to do
// it, in place of the general line.

/** Where OpenRouter's credit is added: its settings' *Credits*. */
internal const val OPENROUTER_CREDITS = "https://openrouter.ai/settings/credits"

/** The failure labels OpenRouter's own line takes the place of: a key refused, no credit. */
private val UNAUTHORIZED = AiErrorKind.UNAUTHORIZED.name.lowercase()
private val BILLING = AiErrorKind.BILLING.name.lowercase()

/** Whether a write that failed for [reason] with [provider] on says so in OpenRouter's own words. */
internal fun saidByOpenRouter(
    provider: ProviderId?,
    reason: String,
): Boolean = provider == ProviderId.OPENROUTER && (reason == UNAUTHORIZED || reason == BILLING)

/**
 * OpenRouter's line for a write that failed for [reason] ([saidByOpenRouter]): the key refused, it
 * may have been deleted there, with the link to *AI services* to sign in again ([onAiServices]); or
 * the account needs credit, with the link to add it on OpenRouter.
 */
@Composable
internal fun OpenRouterFailure(
    reason: String,
    onAiServices: () -> Unit,
) {
    val parts =
        if (reason == UNAUTHORIZED) {
            linkedParts(
                stringResource(R.string.home_openrouter_unauthorized),
                TextPart.Action(
                    stringResource(R.string.home_settings_ai_services_link),
                    OPENROUTER_SIGN_IN_AGAIN_TAG,
                    onAiServices,
                ),
            )
        } else {
            linkedParts(
                stringResource(R.string.home_openrouter_billing),
                TextPart.Link(stringResource(R.string.home_openrouter_credit_link), OPENROUTER_CREDITS),
            )
        }
    LinkedText(
        parts,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(OPENROUTER_FAILURE_TAG),
    )
}

/** The test tag of OpenRouter's line of a write that failed. */
const val OPENROUTER_FAILURE_TAG: String = "openrouter_failure"

/** The test tag of its link to *AI services*. */
const val OPENROUTER_SIGN_IN_AGAIN_TAG: String = "openrouter_sign_in_again"
