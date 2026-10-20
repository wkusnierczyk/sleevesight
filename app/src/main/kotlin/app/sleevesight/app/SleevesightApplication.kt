package app.sleevesight.app

import android.app.Application

/** Holds the process's one [AppGraph]. */
class SleevesightApplication : Application() {
    private val made = lazy { AppGraph(this) }

    val graph: AppGraph by made

    /**
     * Never called on a phone, whose process ends without it; Robolectric calls it after each test,
     * which then ends the graph ([AppGraph.close]): its scopes cancelled, its network client closed.
     */
    override fun onTerminate() {
        if (made.isInitialized()) graph.close()
        super.onTerminate()
    }
}
