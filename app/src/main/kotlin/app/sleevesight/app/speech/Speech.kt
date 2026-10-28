package app.sleevesight.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * The phone's text-to-speech engine, as *Read aloud* (#325) uses it: a seam, so the reading's rules
 * ([ReadAloud]) are tested against a fake. Called on the main thread, and calls back on it.
 */
interface Speech {
    /**
     * Opens the engine, unless it is open; [ready] is called once, with whether it opened: false
     * when the phone has no text-to-speech engine, or it failed to start.
     */
    fun open(ready: (Boolean) -> Unit)

    /** Whether the open engine has a voice for [locale]'s language installed. */
    fun speaks(locale: Locale): Boolean

    /**
     * Speaks [parts] in order, in [locale], in place of anything it was speaking; [done] once the
     * last has been spoken, never after [stop], nor after an error, which [failed] is for.
     */
    fun speak(
        parts: List<String>,
        locale: Locale,
        done: () -> Unit,
        failed: () -> Unit,
    )

    /** Stops speaking at once, dropping what was still to be said. */
    fun stop()

    /** Stops, and releases the engine, which [open] opens again. */
    fun close()
}

/**
 * The parts of Android's `TextToSpeech` that [SystemSpeech] uses: a seam, so its handling of the
 * engine (an engine that is no longer current, a `speak` refused) is tested without one. Its
 * results and [setOnUtteranceProgressListener]'s calls are `TextToSpeech`'s.
 */
interface SpeechEngine {
    fun setAudioAttributes(attributes: AudioAttributes)

    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener)

    fun isLanguageAvailable(locale: Locale): Int

    fun setLanguage(locale: Locale)

    /** `TextToSpeech.speak`: `TextToSpeech.SUCCESS`, or `TextToSpeech.ERROR` when it was not queued. */
    fun speak(
        text: String,
        queueMode: Int,
        utteranceId: String,
    ): Int

    fun stop()

    fun shutdown()
}

/** [SpeechEngine] as Android's `TextToSpeech`, which calls [onInit] with its status once it has started. */
private class AndroidSpeechEngine(
    context: Context,
    onInit: (Int) -> Unit,
) : SpeechEngine {
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    override fun setAudioAttributes(attributes: AudioAttributes) {
        tts.setAudioAttributes(attributes)
    }

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts.setOnUtteranceProgressListener(listener)
    }

    override fun isLanguageAvailable(locale: Locale): Int = tts.isLanguageAvailable(locale)

    override fun setLanguage(locale: Locale) {
        tts.language = locale
    }

    override fun speak(
        text: String,
        queueMode: Int,
        utteranceId: String,
    ): Int = tts.speak(text, queueMode, null, utteranceId)

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() = tts.shutdown()
}

/**
 * [Speech] through Android's `TextToSpeech` ([make], a seam), with the engine and voice chosen in
 * the phone's settings: no engine of the app's own, no permission and no dependency. The engine
 * decides where the text goes: some (Google's network voices) send it to their server to be spoken,
 * which the privacy policy says. Spoken as an assistant's voice ([ATTRIBUTES]), at the media
 * volume, which [MusicFocus] lowers or pauses the music under. The engine's callbacks come on a
 * thread of its own and are handed to the main thread.
 *
 * Each engine made is a generation: a callback from one that is no longer current (closed, or
 * replaced while it started) is ignored, and such an engine that starts anyway is shut down. A
 * `speak` the engine refuses (`TextToSpeech.ERROR`, after which no utterance callback comes) ends
 * the reading as failed at once, and the engine is let go, so the next [open] binds afresh.
 */
class SystemSpeech(
    context: Context,
    private val make: (onInit: (Int) -> Unit) -> SpeechEngine = { onInit -> AndroidSpeechEngine(context, onInit) },
) : Speech {
    private val main = Handler(Looper.getMainLooper())
    private var engine: SpeechEngine? = null
    private var opened = false

    // The current engine's generation: a callback carrying another is from an engine let go.
    private var generation = 0

    // The callers waiting for the engine to open, called once it has.
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    // The reading under way: its utterances' ids, the last of them, and what to call when it ends.
    private val ids = mutableSetOf<String>()
    private var last: String? = null
    private var onDone: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null
    private var count = 0

    override fun open(ready: (Boolean) -> Unit) {
        if (opened) {
            ready(engine != null)
            return
        }
        waiting += ready
        if (engine != null) return
        val made = ++generation
        lateinit var starting: SpeechEngine
        starting = make { status -> main.post { started(made, starting, status == TextToSpeech.SUCCESS) } }
        engine = starting
    }

    // The engine of generation [made] opened, or failed to: one that failed is let go, and the next
    // open tries again; one no longer current is shut down and changes nothing.
    private fun started(
        made: Int,
        starting: SpeechEngine,
        ok: Boolean,
    ) {
        if (made != generation || engine !== starting) {
            starting.shutdown()
            return
        }
        if (ok) {
            opened = true
            starting.setAudioAttributes(ATTRIBUTES)
            starting.setOnUtteranceProgressListener(Progress(made))
        } else {
            letGo()
        }
        val callers = waiting.toList()
        waiting.clear()
        callers.forEach { it(ok) }
    }

    override fun speaks(locale: Locale): Boolean =
        (engine?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE

    override fun speak(
        parts: List<String>,
        locale: Locale,
        done: () -> Unit,
        failed: () -> Unit,
    ) {
        val tts = engine ?: return failed()
        // Whatever it was reading ends unannounced: QUEUE_FLUSH drops it.
        forget()
        tts.setLanguage(locale)
        onDone = done
        onFailed = failed
        for ((i, part) in parts.withIndex()) {
            val id = "read-${++count}"
            ids += id
            if (i == parts.lastIndex) last = id
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(part, mode, id) == TextToSpeech.ERROR) {
                // No callback follows a refused utterance: the reading fails now, once.
                forget()
                tts.stop()
                letGo()
                failed()
                return
            }
        }
    }

    override fun stop() {
        forget()
        engine?.stop()
    }

    override fun close() {
        stop()
        letGo()
        waiting.clear()
    }

    // The engine shut down and forgotten, its callbacks from now on ignored; the next open makes another.
    private fun letGo() {
        engine?.shutdown()
        engine = null
        opened = false
        generation++
    }

    private fun forget() {
        ids.clear()
        last = null
        onDone = null
        onFailed = null
    }

    // The progress of the engine of generation [made], handed to the main thread: the reading ends
    // with its last utterance, or at the first error.
    private inner class Progress(
        private val made: Int,
    ) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post {
                if (made == generation && utteranceId != null && utteranceId == last) {
                    val done = onDone
                    forget()
                    done?.invoke()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = failed(utteranceId)

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) = failed(utteranceId)

        private fun failed(utteranceId: String?) {
            main.post {
                if (made == generation && utteranceId in ids) {
                    val failed = onFailed
                    forget()
                    failed?.invoke()
                }
            }
        }
    }

    private companion object {
        val ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
