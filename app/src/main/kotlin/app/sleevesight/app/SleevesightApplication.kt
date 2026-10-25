package app.sleevesight.app

import android.app.Application
import androidx.annotation.VisibleForTesting

/** Holds the process's one [AppGraph]. */
class SleevesightApplication : Application() {
    /** How the graph is made, on first use; a test may make it with stand-ins first. */
    @VisibleForTesting
    internal var makeGraph: (Application) -> AppGraph = { AppGraph(it) }

    private val made = lazy { makeGraph(this) }

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
