package app.sleevesight.app

import android.app.Application

/** Holds the process's one [AppGraph]. */
class SleevesightApplication : Application() {
    private val made = lazy { AppGraph(this) }

    val graph: AppGraph by made

    /**
     * Never called on a phone, whose process ends without it; Robolectric calls it after each test,
     * which then closes the graph's network client ([AppGraph.close]) rather than leave it open.
     */
    override fun onTerminate() {
        if (made.isInitialized()) graph.close()
        super.onTerminate()
    }
}
