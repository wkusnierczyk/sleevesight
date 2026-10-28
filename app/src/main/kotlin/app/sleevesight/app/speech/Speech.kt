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
 * [Speech] through Android's `TextToSpeech`, with the engine and voice chosen in the phone's
 * settings: no engine of the app's own, no permission and no dependency. The engine decides where
 * the text goes: some (Google's network voices) send it to their server to be spoken, which the
 * privacy policy says. Spoken as an assistant's voice ([ATTRIBUTES]), at the media volume, which
 * [MusicFocus] lowers or pauses the music under. The engine's callbacks come on a thread of its own
 * and are handed to the main thread.
 */
class SystemSpeech(
    private val context: Context,
) : Speech {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var opened = false

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
        engine =
            TextToSpeech(context.applicationContext) { status ->
                main.post { started(status == TextToSpeech.SUCCESS) }
            }
    }

    // The engine opened, or failed to: one that failed is let go, and the next open tries again.
    private fun started(ok: Boolean) {
        val tts = engine ?: return
        if (ok) {
            opened = true
            tts.setAudioAttributes(ATTRIBUTES)
            tts.setOnUtteranceProgressListener(Progress())
        } else {
            tts.shutdown()
            engine = null
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
        tts.language = locale
        onDone = done
        onFailed = failed
        parts.forEachIndexed { i, part ->
            val id = "read-${++count}"
            ids += id
            if (i == parts.lastIndex) last = id
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    override fun stop() {
        forget()
        engine?.stop()
    }

    override fun close() {
        stop()
        engine?.shutdown()
        engine = null
        opened = false
        waiting.clear()
    }

    private fun forget() {
        ids.clear()
        last = null
        onDone = null
        onFailed = null
    }

    // The engine's progress, handed to the main thread: the reading ends with its last utterance,
    // or at the first error.
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post {
                if (utteranceId != null && utteranceId == last) {
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
                if (utteranceId in ids) {
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
