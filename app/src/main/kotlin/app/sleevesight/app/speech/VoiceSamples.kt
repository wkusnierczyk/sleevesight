package app.sleevesight.app.speech

import app.sleevesight.core.settings.ReadAloudMusic
import kotlinx.coroutines.flow.Flow

/** What *Voice*'s row (#787) has read of the phone's text-to-speech engine. */
sealed interface VoicesLoad {
    /** The engine is opening. */
    data object Loading : VoicesLoad

    /** The phone has no engine, or it failed to start. */
    data object NoEngine : VoicesLoad

    /** The engine's [voices], and the name of its default voice, the phone's choice, if it says. */
    data class Loaded(
        val voices: List<EngineVoice>,
        val default: String?,
    ) : VoicesLoad
}

/**
 * The phone's text-to-speech engine as *Voice*'s row and dialog (#787) use it: its voices, whether
 * the phone is [online], and a sample read in a voice. Called on the main thread, and calls back
 * on it. A seam, so the row is tested without an engine.
 */
interface ReadAloudVoices {
    /** Opens the engine, unless it is open, and tells [loaded] what it has. */
    fun load(loaded: (VoicesLoad) -> Unit)

    /** Whether the phone is online, now and as it changes. */
    val online: Flow<Boolean>

    /**
     * Reads [text] in the voice of [id] (null: the phone's choice), as a reading would, in place of
     * any sample, and of any reading under way; [ended] once it ends, however: read to its end,
     * stopped, refused or failed.
     */
    fun sample(
        id: String?,
        text: String,
        ended: () -> Unit,
    )

    /** Stops the sample being read, if any. */
    fun stopSample()

    /** Stops the sample, and releases the engine, which [load] opens again. */
    fun close()
}

/**
 * [ReadAloudVoices] through an engine of its own ([speechOf], given the voice to read in), the
 * music lowered under a sample as under a reading ([focus]); [before] stops a reading under way
 * ([ReadAloud]), so a sample is not read over it. A sample's voice is chosen as a reading's is
 * ([SystemSpeech]): a voice gone, or one with a server version only while offline, reads as the
 * phone's choice.
 */
class SystemReadAloudVoices(
    speechOf: (voice: () -> String?) -> Speech,
    private val focus: MusicFocus,
    override val online: Flow<Boolean>,
    private val before: () -> Unit = {},
) : ReadAloudVoices {
    private var sampled: String? = null
    private val speech = speechOf { sampled }

    // What to call when the sample under way ends; null when none is.
    private var ended: (() -> Unit)? = null
    private var held = false

    override fun load(loaded: (VoicesLoad) -> Unit) =
        speech.open { opened ->
            loaded(if (opened) VoicesLoad.Loaded(speech.voices(), speech.defaultVoice()) else VoicesLoad.NoEngine)
        }

    override fun sample(
        id: String?,
        text: String,
        ended: () -> Unit,
    ) {
        stopSample()
        before()
        sampled = id
        this.ended = ended
        speech.open { opened ->
            // Stopped, or replaced, while the engine opened.
            if (this.ended !== ended) return@open
            if (!opened || !take()) return@open finish()
            speech.speak(
                listOf(text),
                insightLocale(),
                done = { if (this.ended === ended) finish() },
                failed = { if (this.ended === ended) finish() },
            )
        }
    }

    override fun stopSample() {
        if (ended == null) return
        speech.stop()
        finish()
    }

    override fun close() {
        stopSample()
        speech.close()
    }

    // The music lowered under the sample, as *Lower* does under a reading; another app taking the
    // sound (a call) stops it.
    private fun take(): Boolean {
        held = focus.take(ReadAloudMusic.LOWER) { stopSample() }
        return held
    }

    private fun finish() {
        val told = ended
        ended = null
        if (held) {
            held = false
            focus.release()
        }
        told?.invoke()
    }
}
