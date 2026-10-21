package app.sleevesight.app.keyboard

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The keyboard sessions this phone has run (#394), by their service's UUID, which the session's id
 * gives: never the key. A session is run once, even after the app's process has ended, so a code
 * scanned again, whose counter would start over, cannot have messages recorded earlier replayed.
 * Kept in [file], in the app's no-backup storage, the last [LIMIT] only: a few a day at most, and a
 * page reloaded since makes a new id anyway.
 */
class SessionLedger(
    private val file: File,
) {
    private var runs: MutableList<String>? = null

    /**
     * Whether [session] is new, recording it if so. A ledger that cannot be read or written is
     * taken as empty or not kept: refusing every session would leave the keyboard unusable for a
     * risk that needs someone nearby recording.
     */
    @Synchronized
    fun claim(session: KeyboardSession): Boolean {
        val id = session.serviceUuid.toString()
        val known = runs ?: read().also { runs = it }
        if (id in known) return false
        known += id
        while (known.size > LIMIT) known.removeAt(0)
        write(known)
        return true
    }

    private fun read(): MutableList<String> =
        try {
            if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        } catch (_: IOException) {
            mutableListOf()
        }

    private fun write(ids: List<String>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(ids.joinToString("\n", postfix = "\n"))
        } catch (_: IOException) {
            // Kept in memory for this run.
        }
    }

    companion object {
        /** How many sessions are kept. */
        const val LIMIT = 512

        @Volatile private var app: SessionLedger? = null

        /** The app's one ledger. */
        fun of(context: Context): SessionLedger =
            app ?: synchronized(this) {
                app ?: SessionLedger(File(context.noBackupFilesDir, "keyboard-sessions")).also { app = it }
            }
    }
}
