@file:Suppress("TooManyFunctions") // The block, its rows and parts, the names they show, and their test tags.

package app.sleevesight.app.ui.lab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.app.lab.PromptLab
import app.sleevesight.app.ui.settings.DISABLED_ALPHA
import app.sleevesight.app.ui.settings.SwitchRow
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.LabSettings
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.toJavaInstant

/**
 * The prompt lab's block in *Settings → Developer* (#1003), after *Mark developer items*, only in the
 * debug build and only while [lab] is given: in the developer mark with the tag *Debug build*, its
 * switch, *Prompt lab* (#1388, off until turned on, the switch of every other setting), then what it
 * is, and the warning about the Play app; then one row per prompt kind, each opening in place,
 * several at once, saying in short what is in use and what is compared ([KindRow]); then the
 * comparisons logged, with *Export the log* (a JSON file, through the system's file picker) and
 * *Clear the log*, which asks first.
 *
 * With the switch off the kinds' rows do not apply: as any setting that does not apply, they dim, the
 * whole of each, and cannot be changed or opened (an open one shown closed until the switch is on
 * again), their choices kept. The log stays as it is: what was compared before can still be exported
 * or cleared.
 */
@Composable
internal fun PromptLabBlock(lab: PromptLab) {
    if (!BuildConfig.DEBUG) return
    val settings by lab.settings.collectAsState()
    val open = remember { mutableStateMapOf<PromptKind, Boolean>() }
    val enabled = settings.enabled
    DeveloperMark(Modifier.testTag(LAB_BLOCK_TAG), spacing = 8.dp, tag = stringResource(R.string.lab_mark_tag)) {
        SwitchRow(
            title = stringResource(R.string.lab_title),
            summary = stringResource(R.string.lab_switch_summary),
            checked = enabled,
        ) { on -> lab.update { it.withEnabled(on) } }
        Quiet(stringResource(R.string.lab_summary))
        Quiet(stringResource(R.string.lab_twice))
        Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            for (kind in PromptKind.entries) {
                KindRow(kind, settings, enabled && open[kind] == true, { open[kind] = it }) { change ->
                    lab.update(change)
                }
            }
        }
        Logged(lab)
    }
}

// One kind's row: its name and what is in use and compared; open, its variants. It cannot be opened
// while the lab's switch is off.
@Composable
private fun KindRow(
    kind: PromptKind,
    settings: LabSettings,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    update: ((LabSettings) -> LabSettings) -> Unit,
) {
    val name = kindName(kind)
    val inUse = variantShortName(settings.inUse(kind))
    val summary =
        if (compares(kind)) {
            stringResource(
                R.string.lab_kind_summary,
                inUse,
                settings.compared(kind).map { variantShortName(it) }.joinToString(", "),
            )
        } else {
            stringResource(R.string.lab_kind_summary_use, inUse)
        }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .toggleable(value = open, enabled = settings.enabled, role = Role.Button, onValueChange = onOpen)
                .testTag(labKindTag(kind))
                .padding(vertical = 6.dp),
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Quiet(summary)
        }
        if (open) Variants(kind, settings, update)
    }
}

// The variants of an open kind: the release prompt, the drafts and *Custom*, each with *Use* and
// (but for the genre list) *Compare*, its name and what it changes, and its instructions behind
// *Show the instructions*; *Custom*'s editable, with *Reset*.
@Composable
private fun Variants(
    kind: PromptKind,
    settings: LabSettings,
    update: ((LabSettings) -> LabSettings) -> Unit,
) {
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    val lab = LocalPromptLab.current
    val inUse = settings.inUse(kind)
    val compared = settings.compared(kind)
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(stringResource(R.string.lab_use), style = MaterialTheme.typography.labelMedium)
            if (compares(kind)) {
                Text(
                    stringResource(R.string.lab_compare),
                    Modifier.padding(start = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        for (id in LabSettings.ids(kind)) {
            val variant = LabPrompts.variant(kind, id)
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = inUse == id,
                    onClick = {
                        update { it.use(kind, id) }
                        // *Custom* chosen: its instructions as typed are taken.
                        if (id == LabPrompts.CUSTOM) lab?.applyCustom(kind)
                    },
                    modifier = Modifier.testTag(labUseTag(kind, id)),
                )
                if (compares(kind)) {
                    val on = id in compared
                    Checkbox(
                        checked = on,
                        onCheckedChange = { checked -> update { it.compare(kind, id, checked) } },
                        enabled = on || compared.size < LabPrompts.MOST_COMPARED,
                        modifier = Modifier.testTag(labCompareTag(kind, id)),
                    )
                }
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(variantName(id, variant?.name), style = MaterialTheme.typography.bodyMedium)
                    Quiet(variantDescription(id, variant?.description))
                    val showing = shown[id] == true
                    // Read with the variant's name, as there is one for each variant (#1387).
                    val name = variantName(id, variant?.name)
                    PillButton(
                        stringResource(if (showing) R.string.lab_hide_instructions else R.string.lab_show_instructions),
                        onClick = { shown[id] = !showing },
                        modifier = Modifier.padding(top = 4.dp).testTag(labInstructionsTag(kind, id)),
                        named =
                            if (showing) {
                                stringResource(R.string.lab_hide_instructions_named, name)
                            } else {
                                stringResource(R.string.lab_show_instructions_named, name)
                            },
                    )
                    if (showing) Instructions(kind, id, settings, update)
                }
            }
        }
        if (compares(kind)) Quiet(stringResource(R.string.lab_use_compare_hint))
    }
}

// A variant's instructions as sent ({role} read as the release line); *Custom*'s in a field.
@Composable
private fun Instructions(
    kind: PromptKind,
    id: String,
    settings: LabSettings,
    update: ((LabSettings) -> LabSettings) -> Unit,
) {
    if (id != LabPrompts.CUSTOM) {
        val text = settings.instructions(kind, id)?.let { LabPrompts.shown(kind, it) } ?: LabPrompts.release(kind)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val lab = LocalPromptLab.current
    var editing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = settings.custom(kind),
        onValueChange = { text -> update { it.withCustom(kind, text) } },
        label = { Text(stringResource(R.string.lab_custom_label)) },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(labCustomTag(kind))
                // Taken for the pipeline once the field is left, not at each keystroke.
                .onFocusChanged { focus ->
                    if (editing && !focus.isFocused) lab?.applyCustom(kind)
                    editing = focus.isFocused
                },
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Quiet(stringResource(R.string.lab_custom_role))
    PillButton(
        stringResource(R.string.lab_custom_reset),
        onClick = {
            update { it.withCustom(kind, LabPrompts.ROLE) }
            lab?.applyCustom(kind)
        },
        modifier = Modifier.testTag(labResetTag(kind)),
    )
}

// The comparisons logged, and the log's export and clearing.
@Composable
private fun Logged(lab: PromptLab) {
    val log by lab.log.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by rememberSaveable { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Boolean?>(null) }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_TYPE)) { uri ->
            // `wt`: a provider may otherwise leave the end of a longer file it replaces.
            if (uri != null) {
                scope.launch { exported = lab.export { context.contentResolver.openOutputStream(uri, "wt") } }
            }
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.lab_logged_title), style = MaterialTheme.typography.bodyLarge)
        Quiet(
            log.firstOrNull()?.let { first ->
                pluralStringResource(R.plurals.lab_logged_count, log.size, log.size, day(first.time))
            } ?: stringResource(R.string.lab_logged_none),
        )
        // The settings' pills (#1387), each only while there is a log to export or clear.
        Row {
            PillButton(
                label = stringResource(R.string.lab_export),
                onClick = if (log.isNotEmpty()) ({ exporter.launch(exportName(Clock.System.now())) }) else null,
                modifier = Modifier.testTag(LAB_EXPORT_TAG),
            )
            Spacer(Modifier.width(8.dp))
            PillButton(
                label = stringResource(R.string.lab_clear),
                onClick = if (log.isNotEmpty()) ({ asking = true }) else null,
                modifier = Modifier.testTag(LAB_CLEAR_TAG),
                // It deletes the comparisons logged.
                destructive = true,
            )
        }
        exported?.let { Quiet(stringResource(if (it) R.string.lab_exported else R.string.lab_export_failed)) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(stringResource(R.string.lab_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    lab.clear()
                }) { Text(stringResource(R.string.lab_clear_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.lab_clear_confirm_no)) }
            },
        )
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Whether a comparison of [kind] can be started: under an insight, which the genre list has none of. */
internal fun compares(kind: PromptKind): Boolean = kind != PromptKind.GENRE_LIST

/** [kind]'s name in the lab. */
@Composable
internal fun kindName(kind: PromptKind): String =
    stringResource(
        when (kind) {
            PromptKind.KEY -> R.string.lab_kind_key
            PromptKind.SONG -> R.string.lab_kind_song
            PromptKind.ARTIST -> R.string.lab_kind_artist
            PromptKind.ALBUM -> R.string.lab_kind_album
            PromptKind.GENRE_LIST -> R.string.lab_kind_genre_list
            PromptKind.GENRE_REASONS -> R.string.lab_kind_genre_reasons
            PromptKind.EXPLAIN -> R.string.lab_kind_explain
        },
    )

// A variant by its short name, as a kind's row says it: Release, A, B, Custom.
@Composable
private fun variantShortName(id: String): String =
    when (id) {
        LabPrompts.RELEASE -> stringResource(R.string.lab_variant_release)
        LabPrompts.CUSTOM -> stringResource(R.string.lab_variant_custom)
        else -> id
    }

@Composable
private fun variantName(
    id: String,
    name: String?,
): String =
    when (id) {
        LabPrompts.RELEASE -> stringResource(R.string.lab_variant_release)
        LabPrompts.CUSTOM -> stringResource(R.string.lab_variant_custom)
        else -> stringResource(R.string.lab_variant_named, id, name.orEmpty())
    }

@Composable
private fun variantDescription(
    id: String,
    description: String?,
): String =
    when (id) {
        LabPrompts.RELEASE -> stringResource(R.string.lab_variant_release_description)
        LabPrompts.CUSTOM -> stringResource(R.string.lab_variant_custom_description)
        else -> description.orEmpty()
    }

// The first comparison's day, as "Nov 14".
private fun day(at: kotlin.time.Instant): String =
    DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(at.toJavaInstant())

/** The exported log's file name, by the day it was exported. */
internal fun exportName(at: kotlin.time.Instant): String =
    "sleevesight-prompt-lab-" +
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(at.toJavaInstant()) + ".json"

private const val EXPORT_TYPE = "application/json"

/** The test tag of the lab's block in Settings. */
const val LAB_BLOCK_TAG: String = "lab-block"

/** The test tag of *Export the log*. */
const val LAB_EXPORT_TAG: String = "lab-export"

/** The test tag of *Clear the log*. */
const val LAB_CLEAR_TAG: String = "lab-clear"

/** The test tag of [kind]'s row. */
fun labKindTag(kind: PromptKind): String = "lab-kind-${kind.key}"

/** The test tag of *Use* for [kind]'s variant [id]. */
fun labUseTag(
    kind: PromptKind,
    id: String,
): String = "lab-use-${kind.key}-$id"

/** The test tag of *Compare* for [kind]'s variant [id]. */
fun labCompareTag(
    kind: PromptKind,
    id: String,
): String = "lab-compare-${kind.key}-$id"

/** The test tag of [kind]'s *Custom* field. */
fun labCustomTag(kind: PromptKind): String = "lab-custom-${kind.key}"

/** The test tag of *Show the instructions* (or *Hide*) of [kind]'s variant [id]. */
fun labInstructionsTag(
    kind: PromptKind,
    id: String,
): String = "lab-instructions-${kind.key}-$id"

/** The test tag of *Custom*'s *Reset* for [kind]. */
fun labResetTag(kind: PromptKind): String = "lab-reset-${kind.key}"
