package app.sleevesight.app.session

import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionDumpTest {
    @Test
    fun metadataShowsEveryKeyWithItsValue() {
        val dump =
            SessionDump.describe(
                MediaMetadata
                    .Builder(
                        metadata(
                            title = "tetesept",
                            artist = "Advertisement • 2 of 3",
                            durationMs = 28_000,
                            mediaId = "spotify:ad:1",
                        ),
                    ).putLong(ADVERTISEMENT, 1)
                    .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap(Color.RED, size = 8))
                    .build(),
            )
        assertTrue(dump, "android.media.metadata.ADVERTISEMENT=1" in dump)
        assertTrue(dump, "android.media.metadata.MEDIA_ID=spotify:ad:1" in dump)
        assertTrue(dump, "android.media.metadata.ARTIST=Advertisement • 2 of 3" in dump)
        assertTrue(dump, "android.media.metadata.DURATION=28000" in dump)
        assertTrue(dump, "android.media.metadata.ART=bitmap 8x8" in dump)
    }

    @Test
    fun stateNamesItsActions() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
        assertEquals(listOf("pause", "play", "next"), SessionDump.actionNames(actions))
        assertTrue(SessionDump.describe(state(queueItem = 7)).contains("activeItem=7"))
    }

    @Test
    fun queueShowsItsItemsMediaIds() {
        assertEquals(
            "size=1 [3:spotify:track:x:Sundown]",
            SessionDump.describe(listOf(queueItem(3, "Sundown", mediaId = "spotify:track:x"))),
        )
        assertEquals("null", SessionDump.describe(null as MediaMetadata?))
    }

    // `MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT`: the framework's `MediaMetadata` names no such
    // key, but carries it through from a player that sets it.
    private companion object {
        const val ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"
    }
}
