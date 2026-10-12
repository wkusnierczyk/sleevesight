package io.github.wkusnierczyk.sleevesight.spike

import java.util.concurrent.CopyOnWriteArrayList

/** Callbacks the screen registers to redraw when the log or the sessions change. */
class Observers {
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    operator fun plusAssign(listener: () -> Unit) {
        callbacks += listener
    }

    operator fun minusAssign(listener: () -> Unit) {
        callbacks -= listener
    }

    fun fire() = callbacks.forEach { it() }
}
