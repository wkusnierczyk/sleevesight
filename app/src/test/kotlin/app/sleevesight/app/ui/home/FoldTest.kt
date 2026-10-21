package app.sleevesight.app.ui.home

import androidx.compose.runtime.saveable.SaverScope
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fold's moves (#269), and what of it outlives a recreated activity. */
class FoldTest {
    private val page = Fold.PAGE

    @Test
    fun eachFoldShowsItsShareOfTheList() {
        assertEquals(0, Fold.Folded.shown(20))
        assertEquals(page, Fold.Pages(1).shown(20))
        assertEquals(20, Fold.Pages(3).shown(20))
        assertEquals(20, Fold.All.shown(20))
    }

    @Test
    fun moreAddsAScreenfulAndReachingTheEndIsAll() {
        assertEquals(Fold.Pages(1), Fold.Folded.more(20))
        assertEquals(Fold.Pages(2), Fold.Pages(1).more(20))
        assertEquals(Fold.All, Fold.Pages(2).more(2 * page + 1))
        assertEquals(Fold.All, Fold.Pages(2).more(3 * page))
        assertEquals(Fold.All, Fold.All.more(20))
    }

    @Test
    fun reachingALineUnfoldsByWholeScreenfulsAndNeverFoldsBack() {
        assertEquals(Fold.Pages(1), Fold.Folded.reaching(0))
        assertEquals(Fold.Pages(1), Fold.Folded.reaching(page - 1))
        assertEquals(Fold.Pages(2), Fold.Folded.reaching(page))
        assertEquals(Fold.Pages(3), Fold.Pages(1).reaching(2 * page))
        assertEquals(Fold.Pages(3), Fold.Pages(3).reaching(0))
        assertEquals(Fold.All, Fold.All.reaching(0))
    }

    @Test
    fun aFoldKeptForTheNextTrackComesBackAtItsFirstScreenful() {
        assertEquals(Fold.Folded, Fold.Folded.forNextTrack())
        assertEquals(Fold.Pages(1), Fold.Pages(4).forNextTrack())
        assertEquals(Fold.All, Fold.All.forNextTrack())
    }

    @Test
    fun theFoldIsTheTracksOwnUnlessKept() {
        val memory = FoldMemory()
        SourcesFold(memory, sundown, keep = false).set(Fold.Pages(2))
        assertEquals(Fold.Pages(2), SourcesFold(memory, sundown.copy(album = "Other"), keep = false).fold)
        assertEquals(Fold.Folded, SourcesFold(memory, carefree, keep = false).fold)
        assertEquals(Fold.Pages(1), SourcesFold(memory, carefree, keep = true).fold)
    }

    @Test
    fun theFoldAndItsTrackOutliveARecreatedActivity() {
        val scope = SaverScope { true }
        for (fold in listOf(Fold.Folded, Fold.Pages(3), Fold.All)) {
            for (track in listOf(null, sundown, sundown.copy(mediaId = null, queueItemId = 7))) {
                val saved = with(FoldMemory.Saver) { scope.save(FoldMemory(fold, track)) }
                val restored = checkNotNull(FoldMemory.Saver.restore(checkNotNull(saved)))
                assertEquals(fold, restored.fold)
                // What `TrackIdentity.playsOn` reads, so the track restored plays on as it did.
                val identity = { t: NowPlaying -> listOf(t.player, t.mediaId, t.queueItemId, t.title, t.artists) }
                assertEquals(track?.let(identity), restored.track?.let(identity))
            }
        }
    }
}
