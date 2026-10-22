package app.sleevesight.app.keyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    // False once the file could not be read: writing then would replace what it holds with this
    // run's sessions alone.
    private var readable = true

    /**
     * Whether [session] is new, recording it if so. A ledger that cannot be read or written is
     * taken as empty or kept in memory only: refusing every session while storage fails (a full
     * disk) would leave the keyboard unusable, for a risk that needs someone nearby who recorded an
     * earlier session's messages and the process to have ended since.
     */
    @Synchronized
    fun claim(session: KeyboardSession): Boolean {
        val id = session.serviceUuid.toString()
        val known = runs ?: read().also { runs = it }
        if (id in known) return false
        known += id
        while (known.size > LIMIT) known.removeAt(0)
        if (readable) write(known)
        return true
    }

    private fun read(): MutableList<String> =
        try {
            if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        } catch (_: IOException) {
            readable = false
            mutableListOf()
        }

    // Writes a new file beside the ledger, synced, then puts it in the ledger's place in one move:
    // a crash or a full disk leaves the ledger as it was, never cut short.
    private fun write(ids: List<String>) {
        val next = File(file.path + ".new")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(next).use { out ->
                out.write(ids.joinToString("\n", postfix = "\n").toByteArray())
                out.fd.sync()
            }
            Files.move(
                next.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            // Kept in memory for this run; the file keeps what it had.
            next.delete()
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
