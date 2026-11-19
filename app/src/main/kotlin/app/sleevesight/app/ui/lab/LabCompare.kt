@file:Suppress("TooManyFunctions") // The action, the panel and its parts, and their test tags.

package app.sleevesight.app.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.app.settings.ListedModelNames
import app.sleevesight.app.ui.home.AiLabel
import app.sleevesight.app.ui.home.CheckedProse
import app.sleevesight.app.ui.home.Credit
import app.sleevesight.app.ui.home.LocalNoteFocus
import app.sleevesight.app.ui.home.creditText
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.LabMark
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptKind

/**
 * [LabCompare] under [insight], a written text of [kind] on [track], checked against [evidence]: in
 * the debug build only, and only with a track.
 */
@Composable
internal fun LabCompareUnder(
    kind: PromptKind,
    track: TrackState?,
    insight: InsightState.Written,
    evidence: EvidenceSet? = track?.evidence,
) {
    if (BuildConfig.DEBUG && track != null) LabCompare(labSubject(kind, track, insight, evidence))
}

/**
 * *Compare {n} prompts · {n} paid calls* (#1003), under an insight, after *Report the insight*, in
 * the developer mark tagged *Debug build*: only in the debug build, while developer mode is on, for a
 * kind a comparison can start under ([compares]). A tap writes a version with each variant taking
 * part, from the request the insight was written from, one paid call each, stacked in shuffled
 * order and labelled 1, 2, 3 only ([ComparePanel]), in the action's place. Not offered with fewer than
 * two variants taking part. What Home shows is never changed by it: the comparison lives in the log.
 */
@Composable
internal fun LabCompare(subject: LabSubject) {
    // Outside the debug build, none: `BuildConfig.DEBUG` is a constant, so the rest is left out.
    val lab = LocalPromptLab.current?.takeIf { BuildConfig.DEBUG } ?: return
    val on by lab.on.collectAsState()
    if (!on || !compares(subject.kind)) return
    val settings by lab.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val names = remember(context) { ListedModelNames(context) }
    val resources = LocalResources.current
    // One comparison per card shown: evidence landing meanwhile keeps it, which holds its own subject.
    var run by remember(subject.kind, subject.card) { mutableStateOf<Comparing?>(null) }
    // Its calls stop with it, rather than run on unseen and unlogged.
    DisposableEffect(run) {
        val current = run
        onDispose { current?.stop() }
    }
    val count = settings.compared(subject.kind).size
    DeveloperMark(Modifier.fillMaxWidth().testTag(LAB_COMPARE_TAG), tag = stringResource(R.string.lab_mark_tag)) {
        val current = run
        if (current == null) {
            val start = {
                run =
                    Comparing.start(
                        subject,
                        lab.sent(subject.card),
                        settings,
                        scope,
                        failure = { failureText(it, resources) },
                        stopped = resources.getString(R.string.lab_stopped),
                    )
            }
            PillButton(
                onClick = start.takeIf { count >= LabPrompts.FEWEST_COMPARED },
                modifier = Modifier.testTag(LAB_COMPARE_ACTION_TAG),
            ) {
                AiLabel(pluralStringResource(R.plurals.lab_compare_action, count, count))
            }
        } else {
            ComparePanel(
                current,
                credit = { id -> versionCredit(id, names.of(current.model), resources) },
                onShow = { lab.record(current.logged(BuildConfig.VERSION_NAME)) },
                onClose = {
                    current.stop()
                    run = null
                },
            )
        }
    }
}

/**
 * The stacked comparison: its heading and *Close*; what it is; each version ([VersionCard]), then
 * *Stop* while any is writing, *Show the prompts* once all have ended, which names each version's
 * prompt and logs the comparison ([onShow]), and after it, that it was logged.
 */
@Composable
private fun ComparePanel(
    run: Comparing,
    credit: (String) -> String,
    onShow: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(LAB_PANEL_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.lab_compare_title),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            PillButton(
                stringResource(R.string.lab_compare_close),
                onClick = onClose,
                modifier = Modifier.testTag(LAB_CLOSE_TAG),
            )
        }
        if (run.sent == null) {
            Note(stringResource(R.string.lab_compare_unsent))
            return@Column
        }
        Note(pluralStringResource(R.plurals.lab_compare_intro, run.versions.size, run.versions.size))
        for (version in run.versions) {
            HorizontalDivider()
            VersionCard(run, version, credit)
        }
        HorizontalDivider()
        when {
            run.revealed -> Note(stringResource(R.string.lab_logged))
            run.writing -> {
                PillButton(
                    stringResource(R.string.lab_stop),
                    onClick = run::stop,
                    modifier = Modifier.testTag(LAB_STOP_TAG),
                )
            }
            else -> {
                PillButton(
                    stringResource(R.string.lab_show),
                    onClick = {
                        run.revealed = true
                        onShow()
                    },
                    modifier = Modifier.testTag(LAB_SHOW_TAG),
                )
            }
        }
    }
}

// One version: its number (and, once shown, its prompt and credit), what it wrote, or that it is
// writing, or why it could not be with *Retry*; then *Good*, *Bad* and *Add a note*, each fixed once
// the prompts are shown and the comparison logged.
@Composable
private fun VersionCard(
    run: Comparing,
    version: VersionState,
    credit: (String) -> String,
) {
    Column(
        Modifier.fillMaxWidth().testTag(labVersionTag(version.shown)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.lab_version, version.shown),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (run.revealed) {
                Text(
                    revealedName(run.subject.kind, version.id),
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val text = version.text
        val failed = version.failed
        when {
            version.writing -> Busy(stringResource(R.string.lab_writing), MaterialTheme.typography.bodyMedium)
            failed != null -> {
                Text(
                    stringResource(R.string.lab_version_failed, failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!run.revealed) {
                    PillButton(
                        onClick = { run.retry(version) },
                        modifier = Modifier.testTag(labRetryTag(version.shown)),
                    ) {
                        AiLabel(stringResource(R.string.lab_retry))
                    }
                }
            }
            text != null -> Written(run.subject, text)
        }
        if (run.revealed && text != null) {
            Text(
                credit(version.id),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (version.writing) return@Column
        Marks(run, version)
    }
}

// What a version wrote: checked against the insight's evidence where the panel has it, its
// citations shown as the insight's are; else as written. It has no note on its dimmed text, and does
// not take the one of the insight it sits under (#1437): its `?` and outlined chips go to no note.
@Composable
private fun Written(
    subject: LabSubject,
    text: String,
) {
    val evidence = subject.evidence
    val reply = remember(text, evidence) { evidence?.let { GroundingCheck.check(text, it, subject.allowModel) } }
    if (reply is InsightReply.Written) {
        CompositionLocalProvider(LocalNoteFocus provides null) {
            for (paragraph in reply.grounded.paragraphs) CheckedProse(paragraph, reply.grounded, evidence, onCite = {})
        }
    } else {
        Text(AnnotatedString(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Marks(
    run: Comparing,
    version: VersionState,
) {
    val open = !run.revealed
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MarkChip(version, LabMark.GOOD, R.string.lab_good, open)
        MarkChip(version, LabMark.BAD, R.string.lab_bad, open)
        if (!version.noting && version.note.isEmpty() && open) {
            PillButton(
                stringResource(R.string.lab_add_note),
                onClick = { version.noting = true },
                modifier = Modifier.testTag(labNoteTag(version.shown)),
            )
        }
    }
    if (version.noting || version.note.isNotEmpty()) {
        OutlinedTextField(
            value = version.note,
            onValueChange = { version.note = it.take(NOTE_LIMIT) },
            label = { Text(stringResource(R.string.lab_note, version.shown)) },
            enabled = open,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(labNoteFieldTag(version.shown)),
        )
    }
}

@Composable
private fun MarkChip(
    version: VersionState,
    mark: LabMark,
    label: Int,
    open: Boolean,
) {
    val on = version.mark == mark
    // Plain, as the switches (#1387): chosen, filled in the text's colour, its text in the surface's.
    val colors = MaterialTheme.colorScheme
    FilterChip(
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = colors.onSurface,
                selectedContainerColor = colors.onSurface,
                selectedLabelColor = colors.surface,
            ),
        selected = on,
        onClick = { version.mark = if (on) null else mark },
        label = { Text((if (on) "✓ " else "") + stringResource(label)) },
        enabled = open,
        modifier = Modifier.testTag(labMarkTag(version.shown, mark)),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun revealedName(
    kind: PromptKind,
    id: String,
): String =
    when (id) {
        LabPrompts.RELEASE -> stringResource(R.string.lab_revealed_release)
        LabPrompts.CUSTOM -> stringResource(R.string.lab_revealed_custom)
        else -> stringResource(R.string.lab_revealed_variant, id, LabPrompts.variant(kind, id)?.name.orEmpty())
    }

// A version's credit once shown: the release prompt's as the Play build writes it; a variant's with
// "· prompt B" ([labCreditText]).
private fun versionCredit(
    id: String,
    model: String,
    resources: android.content.res.Resources,
): String {
    val credit = creditText(Credit(model), resources)
    return if (id == LabPrompts.RELEASE) credit else labCreditText(credit, id, resources)
}

// Why a version could not be written, as its line says it.
private fun failureText(
    why: Failure,
    resources: android.content.res.Resources,
): String =
    when (why) {
        Failure.TIMED_OUT -> resources.getString(R.string.lab_failed_time)
        Failure.UNREACHED -> resources.getString(R.string.lab_failed_unreached)
        Failure.SEARCHED -> resources.getString(R.string.lab_failed_search)
        Failure.OTHER -> resources.getString(R.string.lab_failed_other)
    }

// A note longer than this is cut, as the reports' are.
private const val NOTE_LIMIT = 2000

/** The test tag of the lab's mark under an insight. */
const val LAB_COMPARE_TAG: String = "lab-compare"

/** The test tag of *Compare {n} prompts*. */
const val LAB_COMPARE_ACTION_TAG: String = "lab-compare-action"

/** The test tag of the comparison's panel. */
const val LAB_PANEL_TAG: String = "lab-panel"

/** The test tag of the panel's *Close*. */
const val LAB_CLOSE_TAG: String = "lab-close"

/** The test tag of *Show the prompts*. */
const val LAB_SHOW_TAG: String = "lab-show"

/** The test tag of *Stop*. */
const val LAB_STOP_TAG: String = "lab-stop"

/** The test tag of version [shown]. */
fun labVersionTag(shown: Int): String = "lab-version-$shown"

/** The test tag of version [shown]'s [mark]. */
fun labMarkTag(
    shown: Int,
    mark: LabMark,
): String = "lab-mark-$shown-${mark.key}"

/** The test tag of version [shown]'s *Add a note*. */
fun labNoteTag(shown: Int): String = "lab-note-$shown"

/** The test tag of version [shown]'s note. */
fun labNoteFieldTag(shown: Int): String = "lab-note-field-$shown"

/** The test tag of version [shown]'s *Retry*. */
fun labRetryTag(shown: Int): String = "lab-retry-$shown"
