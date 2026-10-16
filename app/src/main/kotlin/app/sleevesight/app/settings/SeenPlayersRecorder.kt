package app.sleevesight.app.settings

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Lists each player the session layer has [seen] under *Players to follow*, switched off, through
 * [record] ([SettingsStore.playerSeen]), so the user can switch it on. A player already listed keeps
 * its switch: [record] leaves it alone.
 *
 * Each player is recorded once per run. One whose record fails (the settings file cannot be
 * written) is tried again with the next player seen, and in any case on the next start of the
 * process, when the session layer sees it again; the failure is reported to [warn], never thrown,
 * so it does not end the run.
 */
class SeenPlayersRecorder(
    private val seen: Flow<Set<String>>,
    private val record: suspend (String) -> Unit,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    /** Records the players seen, as they are seen, until the calling coroutine is cancelled. */
    suspend fun run() {
        val recorded = mutableSetOf<String>()
        seen.collect { players ->
            for (player in players - recorded) {
                try {
                    record(player)
                    recorded += player
                } catch (_: IOException) {
                    // No exception text: an I/O message may name a path; the player is enough.
                    warn("Could not list $player under Players to follow")
                }
            }
        }
    }

    private companion object {
        const val TAG = "Sleevesight"
    }
}
