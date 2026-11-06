package app.sleevesight.app.pipeline

import android.util.Log
import app.sleevesight.app.BuildConfig

/**
 * Debug builds only: each attempt of each network request the app makes, as `:net` logs it, under
 * [TAG]: the request, redacted (no key, no secret's name or value, a hidden path as such), its
 * status or error, its time and its attempt, and the wait before a retry. Read it with
 * `adb logcat -s SleevesightNet -v time`, to see which request held a source. A release build logs
 * nothing.
 */
internal object NetDump {
    const val TAG = "SleevesightNet"

    /** Where the client's log lines go (`appPipelineWiring`'s `netLog`): logcat, in a debug build only. */
    val line: (String) -> Unit = if (BuildConfig.DEBUG) { text -> Log.d(TAG, text) } else { _ -> }
}
