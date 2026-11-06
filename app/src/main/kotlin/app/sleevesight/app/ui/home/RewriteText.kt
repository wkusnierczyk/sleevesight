package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.ui.settings.effortLabel
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.WrittenBy

// What the insight's footer says of a rewrite (#854): what one used, and what one would use.

/**
 * "Rewritten with {what}." for an insight written by *Rewrite insight* (#854), or by an automatic
 * write in place of the insight another model kept (#972), in place of *As written previously.*:
 * what the rewrite used that the insight it replaced did not
 * ([InsightState.Written.rewritten]), the model by the one that wrote it. Null for an insight
 * written the first time.
 */
@Composable
internal fun rewrittenLine(insight: InsightState.Written): String? =
    rewriteWhat(insight.rewritten, insight.writtenBy)?.let { stringResource(R.string.home_rewritten_with, it) }

/**
 * "Tap Rewrite insight to have AI rewrite the insight with {what}." while *Rewrite insight* is
 * offered ([offersRewrite], #854): what a rewrite would use now that the insight shown did not
 * (`InsightOffers.rewriteWith`), the model by the one it would write with. Null while it is not
 * offered, or when nothing the footer names would change.
 */
@Composable
internal fun rewriteOfferLine(context: InsightContext): String? {
    val offer = context.offers.rewriteWith?.takeIf { offersRewrite(context) } ?: return null
    return rewriteWhat(offer.parts, offer.with)?.let { stringResource(R.string.home_rewrite_offer, it) }
}

/**
 * [parts] as the footer says them (#854), in their order, joined with ", " and a last "and"; the
 * model part by [with]'s model as *AI services* lists it (as the byline, #811), or its id once no
 * longer listed, then its effort as Settings names the level, in lower case; the model alone with
 * the service's default effort. Null for none.
 */
@Composable
internal fun rewriteWhat(
    parts: Set<RewritePart>,
    with: WrittenBy?,
): String? {
    val context = LocalContext.current
    val names = remember(context) { ListedModelNames(context) }
    val said = parts.sortedBy { it.ordinal }.mapNotNull { partText(it, with, names) }
    return when (said.size) {
        0 -> null
        1 -> said.single()
        else -> stringResource(R.string.home_rewrite_and, said.dropLast(1).joinToString(", "), said.last())
    }
}

@Composable
private fun partText(
    part: RewritePart,
    with: WrittenBy?,
    names: ListedModelNames,
): String? =
    when (part) {
        RewritePart.NEW_FINDINGS -> stringResource(R.string.home_rewrite_part_new_findings)
        RewritePart.UPDATED_SOURCES -> stringResource(R.string.home_rewrite_part_updated_sources)
        RewritePart.MODEL -> with?.let { modelText(it, names) }
        RewritePart.KNOWLEDGE_ON -> stringResource(R.string.home_rewrite_part_knowledge_on)
        RewritePart.KNOWLEDGE_OFF -> stringResource(R.string.home_rewrite_part_knowledge_off)
        RewritePart.INSTRUCTIONS -> stringResource(R.string.home_rewrite_part_instructions)
    }

// "{model} {effort} effort", or the model alone with the service's default effort.
@Composable
private fun modelText(
    by: WrittenBy,
    names: ListedModelNames,
): String {
    val model = names.of(by.model)
    val effort = by.effort ?: return model
    return stringResource(R.string.home_rewrite_model_effort, model, effortLabel(effort).lowercase())
}
