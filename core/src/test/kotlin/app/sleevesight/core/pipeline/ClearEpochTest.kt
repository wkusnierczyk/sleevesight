package app.sleevesight.core.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** *Clear history*'s boundary (#547): work started before it writes nothing after it. */
class ClearEpochTest {
    private val epoch = ClearEpoch()

    @Test
    fun `work started before an advance writes nothing after it, and work started after does`() =
        runTest {
            val before = epoch.mark()
            epoch.advance()
            val after = epoch.mark()

            assertNull(withContext(before) { epoch.ifCurrent(currentCoroutineContext()) { "kept" } })
            assertEquals("kept", withContext(after) { epoch.ifCurrent(currentCoroutineContext()) { "kept" } })
            assertFalse(epoch.isCurrent(before))
            assertTrue(epoch.isCurrent(after))
        }

    @Test
    fun `unmarked work, or work marked by another boundary, is always current`() =
        runTest {
            val other = ClearEpoch().mark()
            epoch.advance()
            assertTrue(epoch.isCurrent(EmptyCoroutineContext))
            assertTrue(epoch.isCurrent(other))
            assertEquals("kept", withContext(other) { epoch.ifCurrent(currentCoroutineContext()) { "kept" } })
        }

    @Test
    fun `an advance waits for a write in progress, which then ends before it`() =
        runTest {
            val hold = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            launch(epoch.mark()) {
                epoch.ifCurrent(currentCoroutineContext()) {
                    hold.await()
                    order += "written"
                }
            }
            runCurrent()
            launch {
                epoch.advance()
                order += "advanced"
            }
            runCurrent()
            assertEquals(emptyList(), order, "the advance waits")

            hold.complete(Unit)
            runCurrent()
            assertEquals(listOf("written", "advanced"), order)
        }
}
