package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.core.pipeline.InsightState

/** "by {model}" under the *Key insights* heading (#811), [model] by the name *AI services* lists it under. */
@Composable
internal fun InsightByline(model: String) {
    val context = LocalContext.current
    val names = remember(context) { ListedModelNames(context) }
    SearchCredit(stringResource(R.string.home_ai_by, names.of(model)), Modifier.testTag(INSIGHT_BY_TAG))
}

/**
 * The id of the model the byline names (#811): the one that wrote [insight] ([InsightState.Written.writtenBy]),
 * an insight kept from an earlier play by another model naming that model, not the one selected now;
 * while it is written, the one being called, from its first words on. Null with no insight: nothing
 * written yet, a failure, a stop, or nothing to write from; and for an insight no pipeline wrote.
 */
internal fun insightModel(insight: InsightState): String? =
    when (insight) {
        is InsightState.Written -> insight.writtenBy?.model
        is InsightState.Writing -> insight.model?.takeIf { insight.partial.isNotBlank() }
        else -> null
    }

/** The test tag of the byline under the *Key insights* heading, "by {model}" (#811). */
const val INSIGHT_BY_TAG: String = "insight-by"
