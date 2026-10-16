package app.sleevesight.ai

/** One server-sent event: its type ([event], `message` when unnamed) and its data lines, joined by `\n`. */
data class ServerSentEvent(
    val event: String,
    val data: String,
)

/**
 * Reads server-sent events from the lines of a stream (`:net`'s `NetClient.stream`), as the HTML
 * standard's event-stream parsing says: `field: value` lines, a blank line ending an event, `:`
 * starting a comment, several `data` lines joined by `\n`, and an event without data dropped. `id`
 * and `retry` are read and ignored: these streams are not resumed. One instance per stream.
 */
class ServerSentEvents {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private var first = true

    /** Takes the next line; returns the event it completes, if it does. */
    fun line(line: String): ServerSentEvent? {
        val text = if (first) line.removePrefix(BOM) else line
        first = false
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        return when {
            text.isEmpty() -> {
                dispatch()
            }

            field == "event" -> {
                event = value
                null
            }

            field == "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
                null
            }

            // A comment (`:` first, an empty field name), `id`, `retry` and unknown fields.
            else -> {
                null
            }
        }
    }

    private fun dispatch(): ServerSentEvent? {
        val completed =
            if (hasData) {
                ServerSentEvent(
                    event?.takeIf { it.isNotEmpty() } ?: MESSAGE,
                    data.toString(),
                )
            } else {
                null
            }
        event = null
        data.clear()
        hasData = false
        return completed
    }

    private companion object {
        const val MESSAGE = "message"
        const val BOM = "\uFEFF"
    }
}
