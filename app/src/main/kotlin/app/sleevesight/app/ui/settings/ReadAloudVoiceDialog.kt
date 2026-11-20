package app.sleevesight.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.speech.VoiceEntry
import app.sleevesight.app.speech.VoiceGroup
import app.sleevesight.app.speech.VoiceMark
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.core.settings.ReadAloudVoice

// *Voice*'s dialog (#787): the phone's choice and the engine's English voices, each with a play
// button that reads the sample in it; and the way to Android's text-to-speech settings.

/** The test tag of a voice's play button in *Voice*'s dialog, by its [id] (null: the phone's choice). */
internal fun voicePlayTag(id: String?): String = "settings-read-aloud-voice-play:${id.orEmpty()}"

/** What [entry] is marked with, joined by " · ": "higher quality · needs the internet". */
@Composable
private fun voiceMarks(entry: VoiceEntry): List<String> =
    entry.marks.map { mark ->
        stringResource(
            when (mark) {
                VoiceMark.HIGHER_QUALITY_ONLINE -> R.string.settings_read_aloud_voice_higher_online
                VoiceMark.HIGHER_QUALITY -> R.string.settings_read_aloud_voice_higher
                VoiceMark.NEEDS_THE_INTERNET -> R.string.settings_read_aloud_voice_internet
            },
        )
    }

/**
 * *Voice*'s dialog (#787): *The phone's choice*, with the voice it is now ([default], if the engine
 * says), then [groups], each under its country's heading, its voices "Voice 1", "Voice 2", each with
 * what it is marked with under it; a play button on each reads the sample in it ([onPlay], null
 * without an engine to read it), and stops it while it reads ([playing]). Choosing one saves it
 * ([onSelect], with its name, said should it go) and closes the dialog. Where the voice chosen
 * cannot read, the row's note is said over the list.
 */
@Suppress("LongParameterList") // What it lists, what is read, and what a choice does.
@Composable
internal fun VoiceDialog(
    state: VoiceState,
    groups: List<VoiceGroup>,
    default: VoiceEntry?,
    playing: String?,
    onPlay: ((String?) -> Unit)?,
    onSelect: (ReadAloudVoice?) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = (state as? VoiceState.Chosen)?.entry?.id
    val top =
        when (state) {
            is VoiceState.Gone -> voiceNote(state)
            is VoiceState.Chosen -> voiceNote(state)
            else -> null
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_read_aloud_voice)) },
        text = {
            Column(modifier = Modifier.heightIn(max = DIALOG_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
                top?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                val phone = stringResource(R.string.settings_read_aloud_voice_phone)
                VoiceOption(
                    label = phone,
                    detail = default?.let { stringResource(R.string.settings_read_aloud_voice_now, voiceName(it)) },
                    named = phone,
                    id = null,
                    selected = selected == null,
                    playing = playing == dialogSample(null),
                    onPlay = onPlay,
                ) { onSelect(null) }
                for (group in groups) {
                    val country = countryName(group.country)
                    Text(
                        country,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                    )
                    for (entry in group.voices) {
                        val name = voiceName(entry)
                        val marks = voiceMarks(entry)
                        val label = stringResource(R.string.settings_read_aloud_voice_number, entry.number)
                        VoiceOption(
                            label = label,
                            detail = marks.joinToString(" · ").ifEmpty { null },
                            named = name,
                            spoken = (listOf(label, country) + marks).joinToString(", "),
                            id = entry.id,
                            selected = selected == entry.id,
                            playing = playing == dialogSample(entry.id),
                            onPlay = onPlay,
                        ) { onSelect(ReadAloudVoice(entry.id, name)) }
                    }
                }
            }
        },
        // A black pill, as Settings' buttons (#1387); the other dialogs follow separately.
        confirmButton = { PillButton(label = stringResource(R.string.settings_cancel), onClick = onDismiss) },
    )
}

/**
 * One voice in [VoiceDialog]: a radio button, [label] and [detail] under it, the whole read as
 * [spoken] and selecting it; then its play button, read "Listen to [named]", or *Stop* while its
 * sample is [playing], a control of its own.
 */
@Suppress("LongParameterList") // What it shows, how it reads, and its two taps.
@Composable
private fun VoiceOption(
    label: String,
    detail: String?,
    named: String,
    id: String?,
    selected: Boolean,
    playing: Boolean,
    onPlay: ((String?) -> Unit)?,
    spoken: String = listOfNotNull(label, detail).joinToString(", "),
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                    .semantics { contentDescription = spoken }
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Black, as the app's controls (#1387), chosen or not.
            RadioButton(
                selected = selected,
                onClick = null,
                colors =
                    RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.onSurface,
                        unselectedColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onPlay != null) {
            val description =
                if (playing) {
                    stringResource(R.string.settings_read_aloud_voice_stop)
                } else {
                    stringResource(R.string.settings_read_aloud_voice_listen_to, named)
                }
            IconButton(onClick = { onPlay(id) }, modifier = Modifier.testTag(voicePlayTag(id))) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_stop_circle else R.drawable.ic_play_arrow),
                    contentDescription = description,
                )
            }
        }
    }
}

// Android's text-to-speech settings (*Accessibility*, *Text-to-speech output*), by the action the
// Settings app gives them; where a phone has no such page, its accessibility settings, else its
// settings.
private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

/** Opens Android's text-to-speech settings (#787), else the nearest page a phone has. */
internal fun openTextToSpeechSettings(context: Context) {
    val actions =
        listOf(
            TTS_SETTINGS,
            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS,
        )
    actions.firstOrNull { action ->
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
