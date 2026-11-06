package app.sleevesight.app.ui.home

import androidx.compose.runtime.saveable.SaverScope
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.pipeline.InsightItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rows' memory as a recreated screen saves and restores it (#948): the items open, the track
 * they are of, and the items whose tap's write is due on it, last; a state saved before those were
 * kept, ending with the track, restores with none due.
 */
class ItemFoldMemorySaverTest {
    private val scope = SaverScope { true }

    private fun saved(memory: ItemFoldMemory): List<*> = with(ItemFoldMemory.Saver) { scope.save(memory) } as List<*>

    @Test
    fun `the items open, the track and the writes due come back as saved`() {
        val memory = ItemFoldMemory(setOf(InsightItem.SONG, InsightItem.ALBUM), sundown, setOf(InsightItem.SONG))
        val back = checkNotNull(ItemFoldMemory.Saver.restore(checkNotNull(saved(memory))))
        assertEquals(memory.open, back.open)
        assertEquals(memory.due, back.due)
        assertEquals(sundown.title, back.track?.title)
        assertEquals(sundown.album, back.track?.album)
    }

    @Test
    fun `a state saved before the writes due were kept restores with none due`() {
        val earlier = listOf<Any?>("SONG") + savedTrack(sundown)
        val back = checkNotNull(ItemFoldMemory.Saver.restore(earlier))
        assertEquals(setOf(InsightItem.SONG), back.open)
        assertEquals(emptySet<InsightItem>(), back.due)
        assertEquals(sundown.title, back.track?.title)
    }
}
