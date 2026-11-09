package app.sleevesight.net

import kotlin.time.Duration

/**
 * An allowance for waiting out a host that answers busy ([NetError.RateLimited]; `HostPolicy.busyWait`,
 * #1120), which the requests that draw on it share: the app's is a track's run's, for all of its
 * MusicBrainz requests together (*Wait when busy*), so a lookup of several requests waits no longer in
 * all than the user allows.
 */
interface BusyWait {
    /** How much longer the requests drawing on it may wait. */
    val left: Duration

    /** Runs [block], a wait before asking the busy host again (the backoff, the turn after it), against [left]. */
    suspend fun <T> waiting(block: suspend () -> T): T
}
