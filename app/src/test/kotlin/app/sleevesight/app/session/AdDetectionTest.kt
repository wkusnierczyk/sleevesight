package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.advertised
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

/**
 * What tells an ad from a track (#880): the advertisement flag, for any player, and Spotify's
 * `spotify:ad:` URI as the media id or the active queue item's. The reports are synthetic, as Spotify
 * Free's ad has not been captured yet (`fixtures/sessions/ads/`).
 */
@RunWith(RobolectricTestRunner::class)
class AdDetectionTest {
    // A tester's ad (2026-11-03), as the player row showed it.
    private val adTexts = metadata(title = "tetesept", artist = "Advertisement • 2 of 3", durationMs = 28_000)

    @Test
    fun `the advertisement flag set to 1 is an ad, for any player`() {
        assertTrue(AdDetection.isAd(SPOTIFY, advertised()))
        assertTrue(AdDetection.isAd(YOUTUBE_MUSIC, advertised()))
    }

    @Test
    fun `the advertisement flag at 0, or absent, is no ad, whatever the texts say`() {
        assertFalse(AdDetection.isAd(SPOTIFY, advertised(flag = 0)))
        assertFalse(AdDetection.isAd(SPOTIFY, metadata()))
        // "Advertisement" is translated, and a 28 s track with no album is still music.
        assertFalse(AdDetection.isAd(SPOTIFY, adTexts))
        assertFalse(AdDetection.isAd(SPOTIFY, null))
    }

    @Test
    fun `a spotify ad URI as the media id is an ad`() {
        assertTrue(AdDetection.isAd(SPOTIFY, metadata(mediaId = "spotify:ad:00000000000000000000000000000001")))
    }

    @Test
    fun `a spotify ad URI as the active queue item's media id is an ad`() {
        val queue =
            listOf(
                queueItem(7, "Sundown", mediaId = "spotify:track:1"),
                queueItem(8, "tetesept", mediaId = "spotify:ad:1"),
            )
        val onTheAd = AdDetection.activeItemMediaId(state(queueItem = 8), queue)
        assertEquals("spotify:ad:1", onTheAd)
        assertTrue(AdDetection.isAd(SPOTIFY, adTexts, onTheAd))
        assertFalse(AdDetection.isAd(SPOTIFY, metadata(), AdDetection.activeItemMediaId(state(queueItem = 7), queue)))
        assertNull("no active item", AdDetection.activeItemMediaId(state(), queue))
    }

    @Test
    fun `a spotify track URI is no ad`() {
        val track = metadata(mediaId = "spotify:track:4uLU6hMCjMI75M1A2tKUQC")
        assertFalse(AdDetection.isAd(SPOTIFY, track, "spotify:track:1"))
    }

    @Test
    fun `another player's media id in spotify's ad form is no ad, the URI rule being spotify's`() {
        assertFalse(AdDetection.isAd(YOUTUBE_MUSIC, metadata(mediaId = "spotify:ad:1"), "spotify:ad:1"))
        assertFalse(AdDetection.isAdId(YOUTUBE_MUSIC, "spotify:ad:1"))
        assertTrue(AdDetection.isAdId(SPOTIFY, "spotify:ad:1"))
    }

    @Test
    fun `the ad keeps nothing of the player's but its length`() {
        assertEquals(AdPlaying(SPOTIFY, 28.seconds), AdPlaying.of(SPOTIFY, adTexts))
        assertEquals(AdPlaying(SPOTIFY, null), AdPlaying.of(SPOTIFY, advertised()))
    }
}
