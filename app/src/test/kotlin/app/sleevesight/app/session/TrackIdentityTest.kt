package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.core.model.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The identity model (#297; `design/architecture.md`, *Identity*) as a table: what is known of the
 * play so far, the next report, how long after the play's start it came, and whether it continues
 * the play. The timing rows (a slot leading the title) are in [PlayIdentitySequencesTest].
 */
class TrackIdentityTest {
    private data class Row(
        val name: String,
        val known: NowPlaying,
        val report: NowPlaying,
        val sinceStart: Duration,
        val continues: Boolean,
    )

    private val ytm = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = YOUTUBE_MUSIC)
    private val spotify = ytm.copy(player = SPOTIFY, mediaId = "spotify:track:1")
    private val late = 5.seconds

    private val rows =
        listOf(
            Row("another player", ytm, ytm.copy(player = SPOTIFY), late, false),
            Row("media ids on both decide: equal", spotify, spotify.copy(duration = 223.seconds), late, true),
            Row("media ids on both decide: different", spotify, spotify.copy(mediaId = "spotify:track:2"), late, false),
            Row(
                "a media id on one side only falls through to the fields",
                spotify,
                spotify.copy(mediaId = null),
                late,
                true,
            ),
            Row("another title", ytm, ytm.copy(title = "Carefree Highway"), late, false),
            Row("a report without artists says nothing", ytm, ytm.copy(artists = ""), late, true),
            Row(
                "artists filled in after the title (search play)",
                ytm.copy(artists = ""),
                ytm.copy(album = "Sundown"),
                150.milliseconds,
                true,
            ),
            Row("known artists, different", ytm, ytm.copy(artists = "Someone Else"), late, false),
            // Edge 1 and the slot out of identity: another queue item under the same track is the same play.
            Row(
                "edge 1, 8: another queue slot under the same track",
                ytm.copy(queueItemId = 4),
                ytm.copy(queueItemId = 5),
                late,
                true,
            ),
            Row(
                "repeat-one: the same slot and track again",
                ytm.copy(queueItemId = 4),
                ytm.copy(queueItemId = 4),
                late,
                true,
            ),
            Row(
                "repeat from search: the same track, a fresh slot",
                ytm.copy(album = "Sundown", queueItemId = 31),
                ytm.copy(album = "Sundown", queueItemId = 62),
                late,
                true,
            ),
            // Edge 2: studio then live, same title and artists, another album long into the play.
            Row(
                "edge 2: another album after the first second",
                ytm.copy(album = "Sundown"),
                ytm.copy(album = "Live"),
                late,
                false,
            ),
            Row(
                "placeholder album, then the real one within the first second",
                ytm.copy(album = "Gordon Lightfoot"),
                ytm.copy(album = "Sundown"),
                290.milliseconds,
                true,
            ),
            Row(
                "real, placeholder, real within 0.7 s",
                ytm.copy(album = "Gordon Lightfoot"),
                ytm.copy(album = "Sundown"),
                700.milliseconds,
                true,
            ),
            Row(
                "the album exactly at the first second still fills in",
                ytm.copy(album = "A"),
                ytm.copy(album = "B"),
                TrackIdentity.FILL_IN,
                true,
            ),
            Row(
                "an album named as its artist is an album like any other",
                ytm.copy(album = "Sundown"),
                ytm.copy(album = "Gordon Lightfoot"),
                late,
                false,
            ),
            Row("a report without an album says nothing", ytm.copy(album = "Sundown"), ytm, late, true),
            Row(
                "a new length or art is an update",
                ytm,
                ytm.copy(duration = 223.seconds, artUri = "content://art/2"),
                late,
                true,
            ),
            // Edge 4: missing fields are not transitive, since the play keeps what it learned.
            Row(
                "edge 4: X, then no artists, then Y, against the play so far",
                TrackIdentity.merged(ytm, ytm.copy(artists = "")),
                ytm.copy(artists = "Someone Else"),
                late,
                false,
            ),
            // Edge 3: comparisons use everything known so far, not the first report.
            Row(
                "edge 3: the album learned after the first report decides later",
                TrackIdentity.merged(ytm, ytm.copy(album = "Sundown")),
                ytm.copy(album = "Live"),
                late,
                false,
            ),
            // Edge 7: a queue entry naming the playing track is the playing track (by its fields).
            Row(
                "edge 7: a queue entry with the playing track's title and artists",
                ytm.copy(album = "Sundown", play = 3),
                ytm.copy(queueItemId = 5),
                late,
                true,
            ),
        )

    @Test
    fun eachRowContinuesThePlayOrNot() {
        for (row in rows) {
            assertEquals(row.name, row.continues, TrackIdentity.continues(row.known, row.report, row.sinceStart))
        }
    }

    @Test
    fun aMergedPlayKeepsWhatItLearned() {
        val known = ytm.copy(album = "Sundown", duration = 223.seconds, mediaId = "m", play = 7)
        val merged = TrackIdentity.merged(known, ytm.copy(artists = "", queueItemId = 9))
        assertEquals(known.copy(queueItemId = 9), merged)
        // A stream's next song under one media id takes nothing from the song before.
        val next = ytm.copy(title = "Carefree Highway", artists = "", mediaId = "m")
        assertEquals(next.copy(play = 7), TrackIdentity.merged(known, next))
    }

    @Test
    fun tagsDecideWhenBothCarryOneElseTheFieldsDo() {
        // Edge 6 and 8: every consumer reads the session layer's tag, so a play filled in is one track.
        assertEquals(
            true,
            TrackIdentity.same(ytm.copy(album = "Gordon Lightfoot", play = 1), ytm.copy(album = "Sundown", play = 1)),
        )
        assertEquals(false, TrackIdentity.same(ytm.copy(play = 1), ytm.copy(play = 2)))
        assertEquals(false, TrackIdentity.same(ytm.copy(play = 1), ytm.copy(player = SPOTIFY, play = 1)))
        // Untagged on one side: by the fields, an album long into the play.
        assertEquals(false, TrackIdentity.same(ytm.copy(album = "Sundown", play = 1), ytm.copy(album = "Live")))
        assertEquals(true, TrackIdentity.same(ytm.copy(play = 1), ytm.copy(queueItemId = 5)))
        // A stream's next song under one media id is the same track, but does not play on.
        val stream = spotify.copy(play = 1)
        assertEquals(true, TrackIdentity.same(stream, stream.copy(title = "Carefree Highway")))
        assertEquals(false, TrackIdentity.playsOn(stream, stream.copy(title = "Carefree Highway")))
    }
}
