package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ModelRefusal
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

/**
 * The models of [choice] a call with the saved key found [refused] (#970), last in [ModelDialog]:
 * under a line saying so, each with why, still to be chosen ([pick]); none, nothing.
 */
@Suppress("LongParameterList") // The provider, its model, the choice, and what a row does.
@Composable
internal fun RefusedModels(
    provider: ProviderId,
    current: String?,
    choice: ModelChoice,
    refused: Map<String, ModelRefusal>,
    named: (String) -> String,
    pick: (String) -> Unit,
) {
    if (choice.refused.isEmpty()) return
    val default = Settings.DEFAULT_MODELS[provider]
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Text(
        stringResource(R.string.settings_model_refused_heading),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    for (id in choice.refused) {
        val isDefault = id == default
        val label = if (isDefault) stringResource(R.string.settings_ai_model_default, named(id)) else named(id)
        val why =
            when (refused[id]) {
                ModelRefusal.ACCOUNT -> R.string.settings_model_why_refused_account
                else -> R.string.settings_model_why_not_served
            }
        RadioRow(
            label,
            selected = current == id || (isDefault && current == null),
            detail = id.takeIf { isDefault || it != label },
            reason = stringResource(why),
        ) { pick(id) }
    }
}
