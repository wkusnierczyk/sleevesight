package io.github.wkusnierczyk.sleevesight.app

import android.app.Application

/** Holds the process's one [AppGraph]. */
class SleevesightApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
