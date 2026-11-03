package app.sleevesight.app.session

import android.graphics.Color
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.PlaybackState
import android.os.Bundle
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
    fun aKeyAndroidDoesNotNameIsShownWithItsType() {
        val dump =
            SessionDump.describe(
                MediaMetadata
                    .Builder(metadata())
                    .putLong("com.spotify.ad", 1)
                    .putString("com.spotify.kind", "ad")
                    .build(),
            )
        assertTrue(dump, "com.spotify.ad=long:1" in dump)
        assertTrue(dump, "com.spotify.kind=text:ad" in dump)
    }

    @Test
    fun ratingsAndLongTextsAreShownAsThemselves() {
        val dump =
            SessionDump.describe(
                MediaMetadata
                    .Builder(metadata(title = "x".repeat(500)))
                    .putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(true))
                    .build(),
            )
        assertTrue(dump, "android.media.metadata.USER_RATING=Rating:style=1 rating=1.0" in dump)
        assertTrue(dump, "android.media.metadata.TITLE=${"x".repeat(120)}…," in dump)
    }

    @Test
    fun stateNamesItsActionsAndKeepsUnknownBits() {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
        assertEquals(listOf("pause", "play", "next"), SessionDump.actionNames(actions))
        assertEquals(
            listOf("prepare", "setPlaybackSpeed", "0x40000"),
            SessionDump.actionNames(PlaybackState.ACTION_PREPARE or (1L shl 22) or (1L shl 18)),
        )
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

    @Test
    fun extrasThatAreNotPlainAreShownByTheirType() {
        val extras =
            Bundle().apply {
                putString("text", "a\nb")
                putInt("number", 3)
                putParcelable("art", bitmap(Color.RED))
            }
        val dump = SessionDump.describe(PlaybackState.Builder(state()).setExtras(extras).build())
        assertTrue(dump, "extras={art=android.graphics.Bitmap, number=3, text=a b}" in dump)
    }

    @Test
    fun aLongReportIsLoggedInParts() {
        val parts = SessionDump.parts("y".repeat(2500))
        assertEquals(listOf(1000, 1000, 500), parts.map { it.length })
        assertEquals(listOf(""), SessionDump.parts(""))
    }

    // `MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT`: the framework's `MediaMetadata` names no such
    // key, but carries it through from a player that sets it.
    private companion object {
        const val ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"
    }
}
