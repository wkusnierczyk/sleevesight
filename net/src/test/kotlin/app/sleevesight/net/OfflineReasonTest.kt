package app.sleevesight.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A failed connection's label reads the same in the release build, which renames the libraries'
 * classes, as in a test, and says whether it was a timeout (#665).
 */
class OfflineReasonTest {
    @Test
    fun `a timeout is named as one, wherever it is among the causes, and counts as timed out`() {
        val timeouts =
            listOf(
                HttpRequestTimeoutException("https://example.org/a", 1_000) to "HttpRequestTimeoutException",
                SocketTimeoutException("silent", null) to "SocketTimeoutException",
                ConnectTimeoutException("no answer") to "ConnectTimeoutException",
            )
        for ((timeout, label) in timeouts) {
            assertEquals(label, offlineReason(timeout))
            assertEquals(label, offlineReason(LibraryFailure(timeout)))
            assertTrue(NetError.Offline(offlineReason(LibraryFailure(timeout))).timedOut, label)
        }
    }

    @Test
    fun `a library's own failure is named by its innermost cause's platform type, which R8 does not rename`() {
        assertEquals("IOException", offlineReason(LibraryFailure()))
        assertEquals("EOFException", offlineReason(LibraryFailure(EOFException("cut"))))
        assertEquals("IOException", offlineReason(IOException("unknown host")))
    }

    @Test
    fun `a failure that is no timeout is not timed out`() {
        assertFalse(NetError.Offline(offlineReason(LibraryFailure(EOFException("cut")))).timedOut)
        assertFalse(NetError.Offline("CancelledBody").timedOut)
    }

    // A library's own exception, as R8 would rename it in the release build.
    private class LibraryFailure(
        cause: Throwable? = null,
    ) : IOException("failed", cause)
}
