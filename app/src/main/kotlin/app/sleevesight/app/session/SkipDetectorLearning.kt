package app.sleevesight.app.session

import android.database.SQLException
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import app.sleevesight.app.diagnostics.SkipLearning
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Where the mark of skip detection's last reset is kept: the id of the newest track change recorded
 * then (`SessionHistory.lastChangeId`), 0 before any reset. Both throw [IOException] on failure, and
 * [read] also when the mark cannot be trusted (its file was found corrupt).
 */
interface LearningMark {
    suspend fun read(): Long

    suspend fun write(id: Long)
}

/**
 * [LearningMark] in a Preferences DataStore of its own: app state, not a user setting, so not in the
 * settings' file or `Settings`. In the app's own files, which the backup and transfer rules exclude,
 * as they do the session history the mark names a change of.
 *
 * Its store ([open]) replaces a file it cannot parse with a mark that [read] refuses, with an
 * [IOException], until one is written over it; not with empty preferences, as the settings' store
 * does, which would read as no reset and relearn from the changes before one.
 */
class DataStoreLearningMark(
    private val store: DataStore<Preferences>,
) : LearningMark {
    override suspend fun read(): Long {
        val id = store.data.first()[KEY] ?: return 0
        if (id == UNREADABLE) throw IOException("The mark's file was corrupt")
        return id
    }

    override suspend fun write(id: Long) {
        store.edit { it[KEY] = id }
    }

    companion object {
        private val KEY = longPreferencesKey("skip_detection.learned_after_change")

        /** No change's id, nor 0: ids start at 1. */
        private const val UNREADABLE = -1L

        /**
         * The mark in [file], in the app's own files: a store whose corruption handler replaces a
         * file it cannot parse with a mark that cannot be read.
         */
        fun open(file: () -> File): DataStoreLearningMark =
            DataStoreLearningMark(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(KEY to UNREADABLE) },
                    produceFile = file,
                ),
            )
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
 * reports false.
 *
 * A mark that cannot be trusted never makes a start learn from changes before a reset, nor hides
 * the changes after one for good:
 * - One that cannot be read at start (its file corrupt, say) relearns nothing, logged by its type,
 *   and is written anew as the newest change's id, as a reset then: nothing that start relearned,
 *   so later starts relearn only what is recorded from then on, as this process learns it.
 * - One above the history's sequence ([SessionHistory.changeSequence]) is from a history created
 *   anew since the reset, so every change recorded came after it: all are relearned from, and the
 *   mark goes back to 0, so it does not come to hide this history's changes once its ids pass it.
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
        withContext(io) { history.changes(SkipDetector.RESTORED, after = trustedMark()).changes }

    /** The mark, unless it cannot be trusted (the class's KDoc); an unreadable one is rethrown. */
    private suspend fun trustedMark(): Long {
        val after =
            try {
                mark.read()
            } catch (e: IOException) {
                rewrite("as of now") { history.lastChangeId() }
                throw e
            }
        if (after <= history.changeSequence()) return after
        Log.w(TAG, "Skip detection's reset mark is from an earlier history: relearning from every change")
        rewrite("to none") { 0 }
        return 0
    }

    /** Writes the mark anew, [what] [id] says; a failure is logged by its type and left for the next start. */
    private suspend fun rewrite(
        what: String,
        id: () -> Long,
    ) {
        try {
            mark.write(id())
        } catch (e: SQLException) {
            Log.w(TAG, "Skip detection could not set its reset mark $what: ${e::class.simpleName}")
        } catch (e: IOException) {
            Log.w(TAG, "Skip detection could not set its reset mark $what: ${e::class.simpleName}")
        }
    }

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
