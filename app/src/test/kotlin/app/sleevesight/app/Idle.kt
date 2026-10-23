package app.sleevesight.app

import android.os.Looper
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/*
 * Waits for state that depends on DataStore, which reads on threads of its own: the app graph's
 * session layer follows the players the settings name, so it reads no session until the settings'
 * first read has arrived. Each step runs the main looper's due tasks, advances its clock by
 * [STEP_MS] (so the main thread's delays, such as a settle window or a retry, pass too) and sleeps
 * as long in real time.
 */

private const val STEP_MS = 10L

private fun step() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
    Thread.sleep(STEP_MS)
}

/** Runs the main looper until [condition] holds, or fails, naming [what], after [timeoutMs]. */
fun idleUntil(
    what: String,
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > end) fail("Not reached within $timeoutMs ms: $what")
        step()
    }
}

/**
 * Runs the main looper for [ms], failing, naming [what], as soon as [invariant] does not hold: for
 * what must not happen, such as a session read before the first run is acknowledged.
 */
fun idleFor(
    what: String,
    ms: Long = 300,
    invariant: () -> Boolean,
) {
    val end = System.currentTimeMillis() + ms
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (!invariant()) fail("Broken: $what")
        if (System.currentTimeMillis() > end) return
        step()
    }
}

/** How long [now] waits for the settings. */
private const val READ_LIMIT_MS = 5_000L

/**
 * The settings as this store reads them now, failing after [READ_LIMIT_MS] rather than waiting on
 * a read that keeps failing: the store retries a failed read for ever, with a growing backoff, so
 * an unbounded `first()` on the main thread could hang a test with no limit.
 */
fun SettingsStore.now(): Settings = runBlocking { withTimeout(READ_LIMIT_MS) { settings.first() } }
