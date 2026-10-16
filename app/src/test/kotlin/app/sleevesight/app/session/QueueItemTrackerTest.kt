package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.queueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueItemTrackerTest {
    private val tracker = QueueItemTracker()

    /** YouTube Music's "Up next", which starts with the playing track. */
    private val upNext = listOf(queueItem(4, "Sundown"), queueItem(5, "Carefree Highway"))

    @Test
    fun anItemTheQueueNamesForAnotherTitleIsNotThisTracks() {
        assertNull(tracker.onMetadata("a", "Sundown", 5, upNext))
        assertEquals(4L, tracker.onMetadata("a2", "sundown ", 4, upNext))
    }

    @Test
    fun anItemTheQueueDoesNotNameOrNamesWithoutATitleIsTaken() {
        assertEquals(9L, tracker.onMetadata("a", "Sundown", 9, upNext))
        assertEquals(3L, tracker.onMetadata("b", "Sundown", 3, listOf(queueItem(3, " "))))
    }

    @Test
    fun noItemWhileStoppedKeepsTheItemPublished() {
        tracker.onMetadata("a", "Sundown", 4, upNext)
        assertEquals(4L, tracker.onState(null, upNext))
        assertEquals(4L, tracker.onMetadata("a", "Sundown", null, upNext))
        assertEquals(4L, tracker.onState(4, upNext))
    }

    @Test
    fun aNewItemBeforeTheNewMetadataWaitsForIt() {
        tracker.onMetadata("a", "Sundown", 4, null)
        assertEquals(4L, tracker.onState(5, null))
        assertEquals(5L, tracker.onMetadata("b", "Carefree Highway", 5, null))
    }

    @Test
    fun newMetadataWithThePreviousTracksItemHasNoItemUntilTheStateMoves() {
        tracker.onMetadata("a", "Sundown", 4, null)
        assertNull(tracker.onMetadata("b", "Carefree Highway", 4, null))
        assertEquals(5L, tracker.onState(5, null))
    }

    @Test
    fun theSameMetadataAgainTakesTheNewItem() {
        // A song queued twice: the item moves, and the player sends the same metadata again.
        tracker.onMetadata("a", "Sundown", 4, null)
        tracker.onState(5, null)
        assertEquals(5L, tracker.onMetadata("a", "Sundown", 5, null))
    }
}
