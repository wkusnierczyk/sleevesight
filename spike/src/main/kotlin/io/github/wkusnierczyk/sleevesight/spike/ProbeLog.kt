package io.github.wkusnierczyk.sleevesight.spike

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The probe's log: one JSON object per line in `files/probe.jsonl`, plus the latest lines in
 * memory for the screen. Read it with `adb shell run-as <package> cat files/probe.jsonl`, or
 * export it from the app.
 */
object ProbeLog {
    private const val FILE_NAME = "probe.jsonl"
    private const val TAIL_LINES = 200

    private val writer = Executors.newSingleThreadExecutor()
    private val tail = ArrayDeque<String>()
    val observers = Observers()
    private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    /** Appends an event of [type], stamped with wall-clock and elapsed-realtime times. */
    fun event(
        type: String,
        fields: JSONObject = JSONObject(),
    ) {
        val line =
            fields
                .put("type", type)
                .put("wallTime", System.currentTimeMillis())
                .put("elapsedRealtime", android.os.SystemClock.elapsedRealtime())
                .toString()
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > TAIL_LINES) tail.removeFirst()
        }
        file?.let { target -> writer.execute { target.appendText(line + "\n") } }
        observers.fire()
    }

    fun tail(): List<String> = synchronized(tail) { tail.toList() }

    fun contents(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        synchronized(tail) { tail.clear() }
        file?.let { target -> writer.execute { target.writeText("") } }
        observers.fire()
    }
}
