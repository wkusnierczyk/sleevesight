package app.sleevesight.app.speech

import app.sleevesight.core.settings.ReadAloudMusic
import kotlinx.coroutines.flow.StateFlow

/**
 * One hold on the music ([inner]) for everything the app speaks (#787): the readings ([ReadAloud])
 * and *Voice*'s samples ([SystemReadAloudVoices]), each through a [party] of its own. When one
 * hands over to the other (a sample started over a reading, a reading over a sample, a sample over
 * a sample), the music is not given back and taken again between them: a hold released while
 * another is [busy], starting or speaking, is kept for it, and its take is granted from that hold.
 * A remote speaker resuming between the two (Spotify Connect, #803) would otherwise still report
 * itself paused when the next took the hold, and play on at full volume under it. The music comes
 * back once the last party releases with none busy: speech ended, stopped, or failed to start.
 * Another app taking the focus stops the party that holds it then. Called on the main thread.
 */
class SharedMusicFocus(
    private val inner: MusicFocus,
    private val busy: () -> Boolean,
) {
    private var held = false
    private var owner: Party? = null

    // The focus taken by another app (a call): the party holding it stops, which gives it back; a
    // hold kept for a party yet to take it is let go, so that party asks for the focus anew rather
    // than speak over the call.
    private fun focusLost() {
        val holder = owner
        if (holder != null) {
            holder.lost?.invoke()
        } else if (held) {
            held = false
            inner.release()
        }
    }

    /** A speaker's focus, which it takes and releases as it would [inner]. */
    fun party(): MusicFocus = Party()

    private inner class Party : MusicFocus {
        var lost: (() -> Unit)? = null

        override fun take(
            how: ReadAloudMusic,
            lost: () -> Unit,
        ): Boolean {
            if (!held) held = inner.take(how) { focusLost() }
            if (held) {
                owner = this
                this.lost = lost
            }
            return held
        }

        override fun release() {
            if (owner === this) {
                owner = null
                lost = null
            }
            if (held && owner == null && !busy()) {
                held = false
                inner.release()
            }
        }

        override val pausedInstead: StateFlow<Boolean> get() = inner.pausedInstead
    }
}
