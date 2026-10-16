package app.sleevesight.app

import android.os.Looper
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * Runs the main looper until [condition] holds, or fails after [timeoutMs]. For state that waits on
 * DataStore, which reads on threads of its own: the app graph's session layer follows the players
 * the settings name, so it reads no session until the settings' first read has arrived.
 */
fun idleUntil(
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > end) fail("Not reached within $timeoutMs ms")
        Thread.sleep(10)
    }
}
