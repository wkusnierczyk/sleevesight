package app.sleevesight.app.session

import android.database.SQLException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import app.sleevesight.app.diagnostics.SkipLearning
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Where the mark of skip detection's last reset is kept: the id of the newest track change recorded
 * then (`SessionHistory.lastChangeId`), 0 before any reset. Both throw [IOException] on failure.
 */
interface LearningMark {
    suspend fun read(): Long

    suspend fun write(id: Long)
}

/**
 * [LearningMark] in a Preferences DataStore of its own: app state, not a user setting, so not in the
 * settings' file or `Settings`. In the app's own files, which the backup and transfer rules exclude,
 * as they do the session history the mark names a change of.
 */
class DataStoreLearningMark(
    private val store: DataStore<Preferences>,
) : LearningMark {
    override suspend fun read(): Long = store.data.first()[KEY] ?: 0

    override suspend fun write(id: Long) {
        store.edit { it[KEY] = id }
    }

    private companion object {
        val KEY = longPreferencesKey("skip_detection.learned_after_change")
    }
}

/**
 * What skip detection has learned, for the diagnostics screen (D15; [SkipLearning]), with a reset
 * that lasts across restarts while the session history stays as it is.
 *
 * The reset marks the record of track changes ([mark]) with the id of the newest change recorded,
 * then forgets what [detector] learned, which also makes a restore under way learn nothing. From
 * then on, at each start, [restore] relearns only from the changes recorded after the mark. An id,
 * not a time: ids only grow, through *Keep history for*'s deletions and *Clear history*
 * (`AUTOINCREMENT`), and no clock moves them. A change judged in the moment of the reset may fall on
 * either side of it. A mark that cannot be written leaves the learning as it was, and the reset
 * reports false; one that cannot be read at start relearns nothing, logged by its type.
 */
class SkipDetectorLearning(
    private val detector: SkipDetector,
    private val history: SessionHistory,
    private val mark: LearningMark,
    private val io: CoroutineContext,
) : SkipLearning {
    override val learnedCrossfades: StateFlow<Map<String, Duration>> = detector.learned

    /** Relearns from the latest [SkipDetector.RESTORED] changes recorded after the mark, read on [io]. */
    suspend fun restore() = detector.restoreFrom { recordedSinceReset() }

    private suspend fun recordedSinceReset(): List<TrackChangeRecord> =
        withContext(io) { history.changes(SkipDetector.RESTORED, after = mark.read()).changes }

    override suspend fun reset(): Boolean =
        try {
            withContext(io) { mark.write(history.lastChangeId()) }
            detector.reset()
            true
        } catch (e: SQLException) {
            Log.w(TAG, "Skip detection could not be reset: ${e::class.simpleName}")
            false
        } catch (e: IOException) {
            Log.w(TAG, "Skip detection could not be reset: ${e::class.simpleName}")
            false
        }

    private companion object {
        const val TAG = "Sleevesight"
    }
}
