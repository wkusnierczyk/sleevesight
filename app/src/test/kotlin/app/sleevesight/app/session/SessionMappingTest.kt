package app.sleevesight.app.session

import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class SessionMappingTest {
    @Test
    fun musicIsATitleAndAnArtist() {
        assertTrue(SessionMapping.reportsMusic(metadata()))
        assertTrue(SessionMapping.reportsMusic(metadata(title = null, displayTitle = "Sundown")))
        // A call (a caller as the title) or a video: no artist.
        assertFalse(SessionMapping.reportsMusic(metadata(title = "+44 20 7946 0000", artist = null)))
        assertFalse(SessionMapping.reportsMusic(metadata(artist = "  ")))
        assertFalse(SessionMapping.reportsMusic(metadata(title = null)))
        assertFalse(SessionMapping.reportsMusic(null))
    }

    // 2026-10-26: a plausible year is 1800 to 2027.
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-10-26T12:00:00Z")
        }

    private fun year(metadata: MediaMetadata) = SessionMapping.year(metadata, clock)

    @Test
    fun theReleasesYearIsYearElseTheYearDateBeginsWith() {
        assertEquals(2026, SessionMapping.nowPlaying(SPOTIFY, metadata(year = 2026), null, clock)?.year)
        assertEquals(2026, year(metadata(date = "20260320T000000")))
        assertEquals(2026, year(metadata(date = "2026-03-20")))
        assertEquals(2026, year(metadata(year = 2026, date = "1990")))
        // No year (players send 0), or none plausible: the date's, else none.
        assertEquals(1990, year(metadata(year = 0, date = "1990")))
        assertEquals(1990, year(metadata(year = 90, date = "1990-11-06")))
        assertNull(year(metadata(date = "0000")))
        assertNull(year(metadata(date = "199")))
        assertNull(year(metadata()))
        assertNull(SessionMapping.nowPlaying(SPOTIFY, metadata(), null, clock)?.year)
    }

    @Test
    fun aYearAfterNextYearIsNotPlausible() {
        // Next year: a release announced ahead.
        assertEquals(2027, year(metadata(year = 2027)))
        assertNull(year(metadata(year = 2028)))
        assertNull(year(metadata(date = "2099-01-01")))
        // An implausible YEAR falls through to the DATE.
        assertEquals(2026, year(metadata(year = 9999, date = "2026-10")))
        assertEquals(1990, year(metadata(year = 2099, date = "1990")))
    }

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

    // #595: a state that says it plays at speed 0 (a remote session may report one) moves at normal
    // speed, as the player says it plays; an unknown position stays unknown, and the bar at its start.
    @Test
    fun aStatePlayingAtSpeedZeroMovesAtNormalSpeed() {
        val playing =
            SessionMapping.playbackInfo(
                SPOTIFY,
                state(PlaybackState.STATE_PLAYING, positionMs = 10_000, speed = 0f, updatedAt = 5_000),
            )
        assertEquals(12.seconds, playing.positionAt(7_000))
        val unknown =
            SessionMapping.playbackInfo(
                SPOTIFY,
                state(
                    PlaybackState.STATE_PLAYING,
                    positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    updatedAt = 5_000,
                ),
            )
        assertNull(unknown.positionAt(7_000))
    }

    @Test
    fun aStateRanksPlayingThenMovingThenTheRest() {
        assertEquals(2, SessionMapping.playingRank(state(PlaybackState.STATE_PLAYING)))
        assertEquals(2, SessionMapping.playingRank(state(PlaybackState.STATE_BUFFERING, speed = 0f)))
        assertEquals(1, SessionMapping.playingRank(state(PlaybackState.STATE_PAUSED, speed = 1f)))
        assertEquals(0, SessionMapping.playingRank(state(PlaybackState.STATE_PAUSED, speed = 0f)))
        assertEquals(0, SessionMapping.playingRank(state(PlaybackState.STATE_STOPPED, speed = 1f)))
        assertEquals(0, SessionMapping.playingRank(null))
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

    @Test
    fun onlyAFallToTheStartOfATrackFromPastItMovesBack() {
        assertTrue(SessionMapping.movedBack(state(positionMs = 180_000), state(positionMs = 0)))
        assertTrue(
            SessionMapping.movedBack(state(PlaybackState.STATE_PAUSED, positionMs = 3_000), state(positionMs = 1_000)),
        )
        // A seek to mid-track, and a fall from no further than the start.
        assertFalse(SessionMapping.movedBack(state(positionMs = 30_000), state(positionMs = 5_000)))
        assertFalse(SessionMapping.movedBack(state(positionMs = 2_000), state(positionMs = 0)))
        // Castbox stalled: `PLAYING` at 0, reported again 4 s later, still at 0.
        assertFalse(
            SessionMapping.movedBack(
                state(positionMs = 0, updatedAt = 1_148_239_036),
                state(positionMs = 0, updatedAt = 1_148_243_292),
            ),
        )
        // Unknown positions never fall back.
        assertFalse(SessionMapping.movedBack(null, state()))
        assertFalse(SessionMapping.movedBack(state(positionMs = 180_000), state(positionMs = -1)))
    }
}
