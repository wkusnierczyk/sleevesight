package app.sleevesight.app.settings

import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Lists each player the session layer has [seen] under *Players*, switched off, through
 * [record] ([SettingsStore.playerSeen]), so the user can switch it on. A player already listed keeps
 * its switch: [record] leaves it alone.
 *
 * Each player is recorded once per run. One whose record fails (the settings file cannot be
 * written) is tried again with the next player seen, and in any case on the next start of the
 * process, when the session layer sees it again; the failure is reported to [warn], never thrown,
 * so it does not end the run. A player [forget] drops, once its app is removed, is recorded again
 * the next time it is seen.
 *
 * [run] and [forget] are called on one thread (the main thread, in the app).
 */
class SeenPlayersRecorder(
    private val seen: Flow<Set<String>>,
    private val record: suspend (String) -> Unit,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val recorded = mutableSetOf<String>()

    /** How often each player was forgotten: a record under way across a [forget] is not kept. */
    private val forgets = mutableMapOf<String, Int>()

    /** Records the players seen, as they are seen, until the calling coroutine is cancelled. */
    suspend fun run() {
        seen.collect { players ->
            for (player in players - recorded) {
                try {
                    val generation = forgets[player]
                    record(player)
                    // Forgotten while its record was under way (its app removed): recorded again when seen again.
                    if (forgets[player] == generation) recorded += player
                } catch (_: IOException) {
                    // No exception text: an I/O message may name a path; the player is enough.
                    warn("Could not list $player under Players")
                }
            }
        }
    }

    /**
     * Forgets that [player] was recorded, once its app is removed (#579), so a reinstall in the same
     * process is listed again when the session layer sees it again; the session layer forgets it
     * too (`SessionRepository.forgetSeen`), or it would not be seen again. A record of it under way
     * as it is forgotten still ends, but does not count as recorded.
     */
    fun forget(player: String) {
        recorded -= player
        forgets[player] = (forgets[player] ?: 0) + 1
    }

    private companion object {
        const val TAG = "Sleevesight"
    }
}
