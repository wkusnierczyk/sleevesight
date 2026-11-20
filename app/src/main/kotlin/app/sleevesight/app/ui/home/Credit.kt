package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.ui.lab.labCreditText
import app.sleevesight.app.ui.lab.labOnNow
import app.sleevesight.app.ui.settings.effortName
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.LabPrompts

// The one credit line of a text an AI model wrote (#1162), under the text, first in its notes (#959).

/**
 * Who wrote a text, and with what (#1162): the [model] by its name as shown; the reasoning [effort]
 * asked for, null at the default (`Settings.creditedEffort`: the provider's scale's, or its own),
 * which is not said; whether a rewrite wrote it
 * ([rewritten]: *Rewrite insight*, #854, or an automatic write in place of another model's, #972) and
 * what that rewrite used that the text it replaced did not ([parts]; the model is not one of them:
 * the credit names it); and, for a genre's reason from web pages (#695) or the check of the track's
 * vocals, that it was written with web search ([web]).
 */
internal data class Credit(
    val model: String,
    val effort: ReasoningEffort? = null,
    val rewritten: Boolean = false,
    val parts: Set<RewritePart> = emptySet(),
    val web: Boolean = false,
)

/**
 * [credit] as the line says it (#1162): "Written by {model}." for a text written the first time,
 * "Rewritten by {model}." for one a rewrite wrote; then, after "with", what else it was written
 * with, joined with ", " and a last "and": the effort when not the service's default, web search,
 * and a rewrite's other reasons in [RewritePart]'s order ("Rewritten by Claude Haiku 5.5 with medium
 * effort and new findings from sources.").
 */
internal fun creditText(
    credit: Credit,
    resources: Resources,
): String {
    val joined = creditWith(credit, resources)
    return when {
        joined == null && credit.rewritten -> resources.getString(R.string.home_credit_rewritten, credit.model)
        joined == null -> resources.getString(R.string.home_credit_written, credit.model)
        credit.rewritten -> resources.getString(R.string.home_credit_rewritten_with, credit.model, joined)
        else -> resources.getString(R.string.home_credit_written_with, credit.model, joined)
    }
}

/**
 * What else [credit]'s text was written with, as the credit says it after "with" ([creditText]):
 * joined with ", " and a last "and"; null for nothing else.
 */
internal fun creditWith(
    credit: Credit,
    resources: Resources,
): String? {
    val with =
        buildList {
            credit.effort?.let {
                add(resources.getString(R.string.home_credit_effort, resources.getString(effortName(it)).lowercase()))
            }
            if (credit.web) add(resources.getString(R.string.home_credit_web_search))
            if (credit.rewritten) {
                credit.parts
                    .sortedBy { it.ordinal }
                    .mapNotNull(::reasonText)
                    .forEach { add(resources.getString(it)) }
            }
        }
    return when (with.size) {
        0 -> null
        1 -> with.single()
        else -> resources.getString(R.string.home_rewrite_and, with.dropLast(1).joinToString(", "), with.last())
    }
}

// A rewrite's reason in the credit's "with" list; none for the model, which the credit names.
private fun reasonText(part: RewritePart): Int? =
    when (part) {
        RewritePart.NEW_FINDINGS -> R.string.home_rewrite_part_new_findings
        RewritePart.UPDATED_SOURCES -> R.string.home_rewrite_part_updated_sources
        RewritePart.MODEL -> null
        RewritePart.KNOWLEDGE_ON -> R.string.home_rewrite_part_knowledge_on
        RewritePart.KNOWLEDGE_OFF -> R.string.home_rewrite_part_knowledge_off
        RewritePart.INSTRUCTIONS -> R.string.home_rewrite_part_instructions
    }

/**
 * The credit of a written insight, the key insight's or an item's (#1162): by the model that wrote it
 * ([model], its id; by default [InsightState.Written.writtenBy]'s), named as *AI services* lists it,
 * or by its id once no longer listed (#811), with the effort it was written with when not the
 * default ([Settings.creditedEffort]); a rewrite's, with
 * what the rewrite used ([InsightState.Written.rewritten]). Null with no model known.
 */
@Composable
internal fun insightCredit(
    insight: InsightState.Written,
    model: String? = insight.writtenBy?.model,
): String? {
    val context = LocalContext.current
    val names = remember(context) { ListedModelNames(context) }
    val id = model ?: return null
    val credit =
        Credit(
            model = names.of(id),
            effort = insight.writtenBy?.let { Settings.creditedEffort(it.provider, it.effort) },
            rewritten = insight.rewritten.isNotEmpty(),
            parts = insight.rewritten,
        )
    return creditText(credit, LocalResources.current)
}

/** [credit] as the line says it ([creditText]). */
@Composable
internal fun creditString(credit: Credit): String = creditText(credit, LocalResources.current)

/**
 * The credit's row, first under the text (#959, #1162): the [credit] in the credit's italics
 * ([SearchCredit]'s style), then on the same line, in upright type, what follows from it ([after]:
 * on a kept insight, a rewrite that failed, what *Look again* found, *Rewrite insight* offered), each
 * after a space; and at the row's end, *Read aloud* ([readAloud]), when given. One text, so a screen
 * reader reads the credit and what follows as the line shows them, then *Read aloud*. With no credit,
 * what follows alone; with neither, *Read aloud* alone at the row's end; nothing with none.
 *
 * In the debug build, for a text the prompt lab's [variant] wrote (#1003), the credit
 * names that variant ("Written by Claude Haiku 5.5 · prompt B."), its text in the developer mark
 * tagged *Debug build*, so a screenshot shows the line is not the Play build's. The variant is the
 * one that wrote the text (`WrittenBy.variant`), whatever the lab is set to now; none for the release
 * prompt's.
 */
@Composable
internal fun CreditRow(
    credit: String?,
    modifier: Modifier = Modifier,
    after: List<String> = emptyList(),
    readAloud: (@Composable () -> Unit)? = null,
    variant: String? = null,
) {
    // The credit naming the variant that wrote the text, if one did; none outside the debug build.
    val resources = LocalResources.current
    // Only while the lab is on (#1388): switched off, the text is about to go, and is not credited to it.
    val labOn = labOnNow()
    val named = variant?.takeIf { BuildConfig.DEBUG && labOn && it != LabPrompts.RELEASE }
    val labbed = if (credit != null && named != null) labCreditText(credit, named, resources) else null
    val credited = labbed ?: credit
    val said = credited != null || after.isNotEmpty()
    if (!said && readAloud == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (said) {
            val text =
                buildAnnotatedString {
                    credited?.let { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(it) } }
                    after.forEach {
                        if (length > 0) append(' ')
                        append(it)
                    }
                }
            val line: @Composable (Modifier) -> Unit = { textModifier ->
                Text(
                    text,
                    textModifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (labbed != null) {
                DeveloperMark(modifier.weight(1f), tag = stringResource(R.string.lab_mark_tag)) { line(Modifier) }
            } else {
                line(modifier.weight(1f))
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        readAloud?.invoke()
    }
}

/** The test tag of the credit under an insight, the key insight's and an item's (#811, #1162). */
const val INSIGHT_BY_TAG: String = "insight-by"
