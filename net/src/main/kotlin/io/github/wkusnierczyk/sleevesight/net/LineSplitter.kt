package io.github.wkusnierczyk.sleevesight.net

/**
 * Splits a body into lines as it arrives, for [NetClient.stream]: a line ends at `\n`, `\r\n` or
 * `\r`, the rule of server-sent events, which covers line-delimited JSON too. Lines are split on the
 * bytes and each is decoded as UTF-8 whole, so a character split between two reads stays intact.
 * The terminators are not part of the lines; a last line without one is passed on by [finish].
 */
internal class LineSplitter(
    private val onLine: suspend (String) -> Unit,
) {
    private var line = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var afterCr = false

    /** Takes the first [count] bytes of [bytes]. */
    suspend fun feed(
        bytes: ByteArray,
        count: Int = bytes.size,
    ) {
        for (i in 0 until count) {
            val byte = bytes[i]
            when {
                byte == LF && afterCr -> {
                    afterCr = false
                }

                byte == LF || byte == CR -> {
                    emit()
                    afterCr = byte == CR
                }

                else -> {
                    afterCr = false
                    append(byte)
                }
            }
        }
    }

    /** Passes on a last line that had no terminator. */
    suspend fun finish() {
        if (size > 0) emit()
    }

    private suspend fun emit() {
        val text = line.decodeToString(0, size)
        size = 0
        onLine(text)
    }

    private fun append(byte: Byte) {
        if (size == line.size) line = line.copyOf(line.size * 2)
        line[size++] = byte
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
    }
}
