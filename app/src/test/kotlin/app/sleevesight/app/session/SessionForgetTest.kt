package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** *Clear history* in the session layer (#547): the last change, which names the track before, goes. */
@RunWith(RobolectricTestRunner::class)
internal class SessionForgetTest : SessionRepositoryFixture() {
    @Test
    fun forgettingDropsTheLastChangeAndTheNextChangeIsPublished() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()
        spotify.settled(carefree)
        val change = repository.lastChange.value
        assertEquals("Carefree Highway", change?.track?.title)

        repository.forgetLastChange()
        assertNull(repository.lastChange.value)

        spotify.settled(Sessions.metadata(title = "Sundown"))
        val next = repository.lastChange.value
        assertEquals("Sundown", next?.track?.title)
    }
}
