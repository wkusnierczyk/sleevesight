package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.sleevesight.app.R
import app.sleevesight.app.speech.ReadAloudVoices
import app.sleevesight.app.speech.VoiceEntry
import app.sleevesight.app.speech.VoiceGroup
import app.sleevesight.app.speech.VoicesLoad
import app.sleevesight.app.speech.englishVoices
import app.sleevesight.app.speech.entry
import app.sleevesight.app.speech.entryOf
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.settings.ReadAloudVoice
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

// *Voice* (#787), the first row under *Read aloud*: the voice that reads insights aloud, one of the
// English voices of the phone's text-to-speech engine, or *The phone's choice*, the engine's own
// default, as before it. A row with its value, opening a dialog of radio buttons; a *Listen* pill
// on the row, and a play button on each voice in the dialog, read a fixed sample line.

/** The engine's voices for *Voice* (#787); none where the screen is shown without them (tests). */
internal val LocalReadAloudVoices = staticCompositionLocalOf<ReadAloudVoices?> { null }

/** What *Voice*'s row (#787) says, by what the engine has and the voice chosen. */
internal sealed interface VoiceState {
    /** The engine not read (yet): the voice stored, by its [name]; null, the phone's choice. */
    data class Stored(
        val name: String?,
    ) : VoiceState

    /** The phone has no text-to-speech engine: the row dims. */
    data object NoEngine : VoiceState

    /** The engine has no English voice: the row dims. */
    data object NoVoice : VoiceState

    /** No voice chosen: the engine's own default reads. */
    data object PhoneChoice : VoiceState

    /** The voice chosen, [name] when chosen, is no longer among the engine's: the phone's choice reads. */
    data class Gone(
        val name: String,
    ) : VoiceState

    /** The voice chosen, [entry]; [offline] while it needs the internet and the phone is offline. */
    data class Chosen(
        val entry: VoiceEntry,
        val offline: Boolean,
    ) : VoiceState
}

/** *Voice*'s state (#787): [chosen] among [groups], the English voices of what the engine has, [load]. */
internal fun voiceState(
    chosen: ReadAloudVoice?,
    load: VoicesLoad?,
    groups: List<VoiceGroup>,
    online: Boolean,
): VoiceState =
    when {
        load == null || load == VoicesLoad.Loading -> VoiceState.Stored(chosen?.name)
        load == VoicesLoad.NoEngine -> VoiceState.NoEngine
        groups.isEmpty() -> VoiceState.NoVoice
        chosen == null -> VoiceState.PhoneChoice
        else ->
            groups.entry(chosen.id)?.let { VoiceState.Chosen(it, offline = !online && it.needsTheInternet) }
                ?: VoiceState.Gone(chosen.name)
    }

/** A country's name, by its ISO [code], in English as the app's texts are; a voice with none, "English". */
internal fun countryName(code: String): String {
    if (code.isEmpty()) return Locale.ENGLISH.getDisplayLanguage(Locale.ENGLISH)
    val name =
        runCatching {
            Locale
                .Builder()
                .setRegion(code)
                .build()
                .getDisplayCountry(Locale.ENGLISH)
        }.getOrNull()
    return name?.takeIf { it.isNotEmpty() } ?: code
}

/** [entry]'s name on the row and in its notes: "Voice 2 (United Kingdom)". */
@Composable
internal fun voiceName(entry: VoiceEntry): String =
    stringResource(R.string.settings_read_aloud_voice_named, entry.number, countryName(entry.country))

/** The test tag of *Voice*'s *Listen* pill. */
internal const val VOICE_LISTEN_TAG = "settings-read-aloud-voice-listen"

/** The test tag of the link to Android's text-to-speech settings in *Voice*'s description and notes. */
internal const val VOICE_TTS_LINK_TAG = "settings-read-aloud-voice-tts"

// What a sample is read for: the row's pill, or a voice's play button in the dialog.
private const val ROW_SAMPLE = "row"
private const val DIALOG_SAMPLE = "dialog:"

/** What the sample of [id]'s voice (null: the phone's choice) is read for in *Voice*'s dialog. */
internal fun dialogSample(id: String?): String = DIALOG_SAMPLE + id.orEmpty()

/**
 * *Voice*'s samples (#787), read through [voices] (none without them, tests): [line] in a voice,
 * [playing] what it is read for while it is read; the same tap again stops it.
 */
private class Samples(
    private val voices: ReadAloudVoices?,
    private val line: String,
) {
    var playing by mutableStateOf<String?>(null)

    val available: Boolean get() = voices != null

    fun play(
        key: String,
        id: String?,
    ) {
        val engine = voices ?: return
        if (playing == key) return engine.stopSample()
        playing = key
        engine.sample(id, line) { if (playing == key) playing = null }
    }

    /** Stops a sample read from the dialog, which is closing. */
    fun stopDialog() {
        if (playing?.startsWith(DIALOG_SAMPLE) == true) voices?.stopSample()
    }
}

/** What the engine has, as *Voice*'s row reads it: [load], its English voices ([groups]), and [online]. */
internal class EngineState(
    val load: VoicesLoad?,
    val groups: List<VoiceGroup>,
    val online: Boolean,
)

/**
 * The engine's state through [voices]: opened while the row is on screen and the app resumed, and
 * let go after, so a voice added in Android's settings shows on the way back.
 */
@Composable
internal fun engineState(voices: ReadAloudVoices?): EngineState {
    var load by remember(voices) { mutableStateOf<VoicesLoad?>(null) }
    if (voices != null) {
        LifecycleResumeEffect(voices) {
            voices.load { load = it }
            onPauseOrDispose { voices.close() }
        }
    }
    val onlineFlow: Flow<Boolean> = remember(voices) { voices?.online ?: flowOf(true) }
    val online by onlineFlow.collectAsState(initial = true)
    val phoneCountry = LocalConfiguration.current.locales[0].country
    val loaded = load as? VoicesLoad.Loaded
    val groups =
        remember(loaded, phoneCountry) {
            loaded?.voices?.let { englishVoices(it, phoneCountry, ::countryName) }.orEmpty()
        }
    return EngineState(load, groups, online)
}

/**
 * *Voice* (#787): its value (the voice chosen, named "Voice 2 (United Kingdom)", or *The phone's
 * choice*), its description with the link to Android's text-to-speech settings, a note where the
 * voice chosen cannot read (it needs the internet and the phone is offline; it is gone), and the
 * *Listen* pill, which reads the sample in it. With no English voice or no engine, the row dims,
 * shows *The phone's choice*, and says so, with the link. A tap opens [VoiceDialog].
 */
@Composable
internal fun VoiceRow(
    settings: Settings,
    update: Update,
) {
    val voices = LocalReadAloudVoices.current
    val engine = engineState(voices)
    val chosen = settings.readAloudVoice
    val state = voiceState(chosen, engine.load, engine.groups, engine.online)
    val dim = state == VoiceState.NoEngine || state == VoiceState.NoVoice
    val context = LocalContext.current
    val openTts = { openTextToSpeechSettings(context) }
    val ttsLink = stringResource(R.string.settings_read_aloud_voice_link)
    val line = stringResource(R.string.settings_read_aloud_voice_sample)
    val samples = remember(voices, line) { Samples(voices, line) }
    var open by remember { mutableStateOf(false) }
    val value = voiceValue(state)
    ValueRow(
        title = stringResource(R.string.settings_read_aloud_voice),
        value = value,
        summary = stringResource(R.string.settings_read_aloud_voice_summary, ttsLink),
        note = voiceNote(state),
        summaryLink = TextPart.Action(ttsLink, VOICE_TTS_LINK_TAG, openTts),
        noteLink = voiceNoteLink(state, openTts),
        trailing = {
            ListenPill(
                value,
                playing = samples.playing == ROW_SAMPLE,
                onClick = if (dim || !samples.available) null else ({ samples.play(ROW_SAMPLE, chosen?.id) }),
            )
        },
        onClick = if (dim) null else ({ open = true }),
    )
    if (open) {
        val close = {
            samples.stopDialog()
            open = false
        }
        VoiceDialog(
            state = state,
            groups = engine.groups,
            default = engine.groups.entryOf((engine.load as? VoicesLoad.Loaded)?.default),
            playing = samples.playing,
            onPlay = if (samples.available) ({ id -> samples.play(dialogSample(id), id) }) else null,
            onSelect = { voice ->
                update { it.copy(readAloudVoice = voice) }
                close()
            },
            onDismiss = close,
        )
    }
}

/** *Voice*'s value in [state]: the voice chosen, or *The phone's choice*. */
@Composable
private fun voiceValue(state: VoiceState): String {
    val phone = stringResource(R.string.settings_read_aloud_voice_phone)
    return when (state) {
        is VoiceState.Stored -> state.name ?: phone
        is VoiceState.Chosen -> voiceName(state.entry)
        else -> phone
    }
}

/** The link in *Voice*'s note in [state], to Android's text-to-speech settings ([open]), if any. */
@Composable
private fun voiceNoteLink(
    state: VoiceState,
    open: () -> Unit,
): TextPart.Linked? =
    when (state) {
        VoiceState.NoEngine ->
            TextPart.Action(stringResource(R.string.settings_read_aloud_voice_no_engine_link), VOICE_TTS_LINK_TAG, open)
        VoiceState.NoVoice ->
            TextPart.Action(
                stringResource(R.string.settings_read_aloud_voice_link),
                VOICE_TTS_LINK_TAG,
                open,
            )
        else -> null
    }

/**
 * *Voice*'s *Listen* pill: read "Listen to [value]", or *Stop* while its sample is [playing]; a null
 * [onClick] disables it.
 */
@Composable
private fun ListenPill(
    value: String,
    playing: Boolean,
    onClick: (() -> Unit)?,
) {
    val label =
        stringResource(
            if (playing) R.string.settings_read_aloud_voice_stop else R.string.settings_read_aloud_voice_listen,
        )
    PillButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.testTag(VOICE_LISTEN_TAG),
        named = if (playing) label else stringResource(R.string.settings_read_aloud_voice_listen_to, value),
    )
}

/** The note under *Voice*'s row in [state], if any. */
@Composable
internal fun voiceNote(state: VoiceState): String? =
    when (state) {
        VoiceState.NoEngine ->
            stringResource(
                R.string.settings_read_aloud_voice_no_engine,
                stringResource(R.string.settings_read_aloud_voice_no_engine_link),
            )
        VoiceState.NoVoice ->
            stringResource(
                R.string.settings_read_aloud_voice_no_voice,
                stringResource(R.string.settings_read_aloud_voice_link),
            )
        is VoiceState.Gone -> stringResource(R.string.settings_read_aloud_voice_gone, state.name)
        is VoiceState.Chosen ->
            if (state.offline) {
                stringResource(
                    R.string.settings_read_aloud_voice_offline,
                    voiceName(state.entry),
                )
            } else {
                null
            }
        else -> null
    }
