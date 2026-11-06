package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.ui.settings.providerName
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.pipeline.InsightState

// The insight card's note of a selected model found unavailable (#540).

/** Whether [insight] is none to show, so the note takes its place: nothing written, failed or stopped. */
internal fun withoutInsight(insight: InsightState): Boolean =
    when (insight) {
        is InsightState.Idle -> !insight.canWrite
        is InsightState.Failed, InsightState.Stopped -> true
        else -> false
    }

/**
 * Why a rewrite ended without an insight ([reason]), as a footer says it after *As written previously.*
 * (`rewriteFailure`), unless the service refused the model (`model_not_found`, `model_refused`) while
 * it is found unavailable ([unavailable], #969): the note under the insight ([ModelUnavailable]) says
 * that then, and points to *AI services*, where the reason alone read as though the app made the
 * model up. Null for none to say.
 */
internal fun rewriteFailureShown(
    reason: String?,
    unavailable: UnavailableModel?,
): String? = reason?.takeUnless { unavailable != null && it in MODEL_REFUSALS }

// The failure labels of a model the service does not know or refuses (`AiErrorKind`'s, in lower case).
private val MODEL_REFUSALS: Set<String> =
    setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED).map { it.name.lowercase() }.toSet()

/**
 * The switched-on service's model is found unavailable (#540): "The currently selected Anthropic
 * model Claude Haiku 5.5 is not available. Please select another model or provider in AI
 * services.", with *AI services* a link to the settings ([onSettings]), which Home opens at the
 * service's model row (#581).
 */
@Composable
internal fun ModelUnavailable(
    unavailable: UnavailableModel,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val names = remember(context) { ListedModelNames(context) }
    val place = stringResource(R.string.home_model_unavailable_place)
    val sentence =
        stringResource(
            R.string.home_model_unavailable,
            stringResource(providerName(unavailable.provider)),
            names.of(unavailable.model),
            place,
        )
    val at = sentence.lastIndexOf(place)
    val parts =
        if (at < 0) {
            listOf(TextPart.Words(sentence))
        } else {
            listOf(
                TextPart.Words(sentence.substring(0, at)),
                TextPart.Action(place, MODEL_SETTINGS_TAG, onSettings),
                TextPart.Words(sentence.substring(at + place.length)),
            )
        }
    LinkedText(
        parts,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(MODEL_UNAVAILABLE_TAG),
    )
}

/** The test tag of the note that the selected model is not available (#540). */
const val MODEL_UNAVAILABLE_TAG: String = "model_unavailable"

/** The test tag of that note's link to the settings. */
const val MODEL_SETTINGS_TAG: String = "model_settings"
