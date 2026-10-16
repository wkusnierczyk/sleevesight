package app.sleevesight.app.session

import android.graphics.Color
import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionMappingTest {
    @Test
    fun spotifysMetadataMapsToNowPlaying() {
        val metadata =
            metadata(
                title = "Sundown",
                artist = "Gordon Lightfoot, Someone Else",
                album = "Sundown",
                durationMs = 223_000,
                mediaId = "spotify:track:1",
                albumArt = bitmap(Color.RED),
                albumArtUri = "content://com.spotify.mobile.android.mediaapi/v1/image/1",
            )
        assertEquals(
            NowPlaying(
                title = "Sundown",
                artists = "Gordon Lightfoot, Someone Else",
                album = "Sundown",
                duration = 223.seconds,
                player = SPOTIFY,
                mediaId = "spotify:track:1",
                queueItemId = 7,
                artUri = "content://com.spotify.mobile.android.mediaapi/v1/image/1",
            ),
            SessionMapping.nowPlaying(SPOTIFY, metadata, 7),
        )
    }

    @Test
    fun missingAndBlankValuesAreAbsent() {
        val track =
            SessionMapping.nowPlaying(
                SPOTIFY,
                metadata(title = "Sundown", artist = null, album = " ", durationMs = 0, mediaId = ""),
                null,
            )
        assertEquals(NowPlaying(title = "Sundown", artists = "", player = SPOTIFY), track)
    }

    @Test
    fun theDisplayTitleStandsInForAMissingTitle() {
        val track = SessionMapping.nowPlaying(SPOTIFY, metadata(title = null, displayTitle = "Episode 4"), null)
        assertEquals("Episode 4", track?.title)
    }

    @Test
    fun aSessionWithoutATitleNamesNoTrack() {
        // As the Sonos session that sent only art.
        assertNull(
            SessionMapping.nowPlaying(
                SPOTIFY,
                metadata(title = null, artist = null, albumArt = bitmap(Color.RED)),
                null,
            ),
        )
        assertNull(SessionMapping.nowPlaying(SPOTIFY, null, null))
    }

    @Test
    fun theActiveQueueItemComesFromThePlaybackStateAndMinusOneIsNone() {
        assertNull(SessionMapping.activeItem(state(queueItem = -1)))
        assertNull(SessionMapping.activeItem(null))
        assertEquals(3L, SessionMapping.activeItem(state(queueItem = 3)))
    }

    @Test
    fun theArtUriIsTheAlbumArtUriElseTheArtUri() {
        assertEquals("https://a/1", SessionMapping.nowPlaying(SPOTIFY, metadata(artUri = "https://a/1"), null)?.artUri)
        assertEquals(
            "content://b/2",
            SessionMapping
                .nowPlaying(
                    SPOTIFY,
                    metadata(albumArtUri = "content://b/2", artUri = "https://a/1"),
                    null,
                )?.artUri,
        )
    }

    @Test
    fun playbackStateMapsToPlaybackInfo() {
        val info =
            SessionMapping.playbackInfo(
                SPOTIFY,
                state(PlaybackState.STATE_PAUSED, positionMs = 61_500, speed = 0f, updatedAt = 1_000),
            )
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, 61_500.milliseconds, 1_000, 0f), info)
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.NONE), SessionMapping.playbackInfo(SPOTIFY, null))
        assertEquals(
            PlaybackStatus.OTHER,
            SessionMapping.playbackInfo(SPOTIFY, state(PlaybackState.STATE_SKIPPING_TO_NEXT)).status,
        )
        assertNull(
            SessionMapping.playbackInfo(SPOTIFY, state(positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN)).position,
        )
    }

    @Test
    fun thePositionIsExtrapolatedOnlyWhilePlaying() {
        val playing =
            SessionMapping.playbackInfo(
                SPOTIFY,
                state(PlaybackState.STATE_PLAYING, positionMs = 10_000, updatedAt = 5_000),
            )
        assertEquals(12.seconds, playing.positionAt(7_000))
        assertEquals(10.seconds, playing.positionAt(4_000))
        val paused =
            SessionMapping.playbackInfo(
                SPOTIFY,
                state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f),
            )
        assertEquals(10.seconds, paused.positionAt(99_000))
    }

    @Test
    fun theQueueAndItsTitleMapWherePublished() {
        val queue =
            SessionMapping.queue(
                listOf(
                    queueItem(11, "Carefree Highway", "Gordon Lightfoot", "spotify:track:2"),
                    queueItem(12, "Rainy Day People"),
                ),
                "Play Queue",
            )
        assertEquals(
            SessionQueue(
                "Play Queue",
                listOf(
                    QueueEntry(11, "Carefree Highway", "Gordon Lightfoot", "spotify:track:2"),
                    QueueEntry(12, "Rainy Day People", null, null),
                ),
            ),
            queue,
        )
        assertNull(SessionMapping.queue(null, null))
        assertEquals(SessionQueue("Up next", emptyList()), SessionMapping.queue(null, "Up next"))
        assertEquals(SessionQueue(null, emptyList()), SessionMapping.queue(emptyList(), " "))
    }

    @Test
    fun theSessionsBitmapComesFirstThenContentThenHttp() {
        val albumArt = bitmap(Color.RED)
        val sources =
            SessionMapping.artSources(
                metadata(
                    albumArt = albumArt,
                    art = bitmap(Color.BLUE),
                    albumArtUri = "https://cdn/1",
                    artUri = "content://p/1",
                ),
            )
        assertEquals(
            listOf(ArtSource.Image(albumArt), ArtSource.Content("content://p/1"), ArtSource.Http("https://cdn/1")),
            sources,
        )
        assertSame(albumArt, (sources.first() as ArtSource.Image).bitmap)
    }

    @Test
    fun theArtBitmapStandsInForAMissingAlbumArtBitmap() {
        val art = bitmap(Color.BLUE)
        assertEquals(listOf(ArtSource.Image(art)), SessionMapping.artSources(metadata(art = art)))
    }

    @Test
    fun withoutABitmapTheUrisAreTriedAndOtherSchemesSkipped() {
        assertEquals(
            listOf(ArtSource.Http("HTTP://cdn/2")),
            SessionMapping.artSources(metadata(albumArtUri = "file:///sdcard/a.jpg", artUri = "HTTP://cdn/2")),
        )
        assertEquals(emptyList<ArtSource>(), SessionMapping.artSources(metadata()))
        assertEquals(emptyList<ArtSource>(), SessionMapping.artSources(null))
    }

    @Test
    fun imagesCompareByContent() {
        assertEquals(ArtSource.Image(bitmap(Color.RED)), ArtSource.Image(bitmap(Color.RED)))
        assertNotEquals(ArtSource.Image(bitmap(Color.RED)), ArtSource.Image(bitmap(Color.BLUE)))
    }
}
