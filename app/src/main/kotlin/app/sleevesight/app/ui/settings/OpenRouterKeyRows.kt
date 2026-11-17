package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.KeyRefusals
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// *AI services* → OpenRouter (#1157): the link to its key, as every service's, and above it, once
// OpenRouter has refused the key saved, that it has (#1397: its key is pasted, as the others').

/** Whether OpenRouter refused its saved key since ([refusals]); false without them (tests, previews). */
@Composable
internal fun openRouterRefused(refusals: KeyRefusals?): Boolean {
    val refused by (refusals?.refused ?: NO_REFUSALS).collectAsState(initial = emptySet())
    return ProviderId.OPENROUTER in refused
}

// No key refused, where the screen is shown without the refusals (tests, previews).
private val NO_REFUSALS: Flow<Set<ProviderId>> = flowOf(emptySet())

/** Whether OpenRouter refused its saved key, for its rows; false where the screen is shown without it. */
internal val LocalOpenRouterRefused = staticCompositionLocalOf { false }

/**
 * OpenRouter's part under its switch, by [key]'s state: with a key saved and refused by OpenRouter
 * since ([LocalOpenRouterRefused]), that it was, in the error colour; then the link to the key under
 * *Secrets* ([KeyLink]). Nothing before the keys' states are read.
 */
@Composable
internal fun OpenRouterKeyRows(key: SecretState?) {
    if (key == null) return
    val refused = LocalOpenRouterRefused.current && keySaved(key)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(OPENROUTER_ROWS_TAG)) {
        if (refused) {
            Text(
                stringResource(R.string.settings_openrouter_key_refused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.testTag(OPENROUTER_PROBLEM_TAG).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        KeyLink(SecretId.OPENROUTER, key)
    }
}

/** The test tags of OpenRouter's part under its switch. */
const val OPENROUTER_ROWS_TAG: String = "openrouter_rows"
const val OPENROUTER_PROBLEM_TAG: String = "openrouter_problem"
