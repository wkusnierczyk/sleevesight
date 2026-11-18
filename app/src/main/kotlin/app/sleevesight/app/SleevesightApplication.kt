package app.sleevesight.app

import android.app.Application
import androidx.annotation.VisibleForTesting
import app.sleevesight.app.secrets.SignInCallbacks

/** Holds the process's one [AppGraph]. */
class SleevesightApplication : Application() {
    /** How the graph is made, on first use; a test may make it with stand-ins first. */
    @VisibleForTesting
    internal var makeGraph: (Application) -> AppGraph = { AppGraph(it) }

    private val made = lazy { makeGraph(this) }

    val graph: AppGraph by made

    override fun onCreate() {
        super.onCreate()
        // OpenRouter's answer to a sign-in (#1157), and the sign-in kept across the process's end,
        // for the main activity; the graph is read only once it is created.
        registerActivityLifecycleCallbacks(SignInCallbacks({ it is MainActivity }, { graph.openRouterLogin }))
    }

    /**
     * Never called on a phone, whose process ends without it; Robolectric calls it after each test,
     * which then ends the graph ([AppGraph.close]): its scopes cancelled, its network client closed.
     */
    override fun onTerminate() {
        if (made.isInitialized()) graph.close()
        super.onTerminate()
    }
}
