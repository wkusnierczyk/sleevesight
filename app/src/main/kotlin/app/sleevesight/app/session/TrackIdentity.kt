package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a report is the track already playing: one model for the session layer and everything
 * that reads it (`design/architecture.md`, *Identity*; `design/design.md`, *Track changes*). It
 * cares what is playing (the recording), not how often it played nor which queue slot holds it.
 *
 * The session layer judges each report against the **play** it may continue, with everything known
 * of that play so far ([continues], then [merged]), and tags every report it publishes with the play
 * it belongs to ([NowPlaying.play]). Two reports it tagged are the same track when their plays are
 * ([same]); so every consumer (skip detection, the session history, the pipeline, the home screen)
 * follows the session layer's verdict rather than judging two reports alone. Reports nothing tagged
 * (a queue entry, a row read back, the CLI) are judged by their fields, as [continues] judges a
 * report long into a play.
 *
 * The rules, in order:
 * 1. tracks of different players differ;
 * 2. the media id decides when both send one;
 * 3. else the title must match exactly;
 * 4. artists: a report without them says nothing; known and different, another track;
 * 5. album: a report without one says nothing; a new album within [FILL_IN] of the play's start
 *    fills the play in (YouTube Music sends the artist as the album first, the real album 190 to
 *    290 ms later); after that, a new album under the same title and artists is another track (a
 *    studio, then a live take). Albums are never compared by name: an album named as its artist is
 *    an album like any other.
 *
 * The active queue item never decides: a repeat (repeat-one, or the same track again right after
 * itself, from another slot) is one play. The session layer still uses it to time a change.
 */
object TrackIdentity {
    /**
     * How long from a play's start a new album fills it in rather than starting another: past YouTube
     * Music's placeholder album (190 to 290 ms; once, at the start of a session, real, placeholder,
     * real within 0.7 s), short of any real second take. Not a setting: it only aligns one player's
     * reports.
     */
    val FILL_IN: Duration = 1.seconds

    /**
     * Whether [a] and [b] are the same track: the same play when the session layer tagged both
     * ([NowPlaying.play]); else, by their fields, as [continues] judges a report long into a play.
     */
    fun same(
        a: NowPlaying,
        b: NowPlaying,
    ): Boolean =
        when {
            a.player != b.player -> false
            a.play != null && b.play != null -> a.play == b.play
            else -> continues(a, b, Duration.INFINITE)
        }

    /**
     * Whether [report] continues the play [known] holds (everything known of it so far), [sinceStart]
     * after the report that began the play: the rules in the KDoc above.
     */
    fun continues(
        known: NowPlaying,
        report: NowPlaying,
        sinceStart: Duration,
    ): Boolean =
        when {
            known.player != report.player -> false
            known.mediaId != null && report.mediaId != null -> known.mediaId == report.mediaId
            known.title != report.title -> false
            differ(known.artists.ifEmpty { null }, report.artists.ifEmpty { null }) -> false
            differ(known.album, report.album) -> sinceStart <= FILL_IN
            else -> true
        }

    /**
     * [report], which [continues] the play [known], with what it leaves out kept from [known]: the
     * artists, the album, the media id, the length and the year, so a later report never takes
     * from the play what it learned. A report under the same media id with another title (a
     * stream's next song) takes nothing from the song before. The play's tag is kept.
     */
    fun merged(
        known: NowPlaying,
        report: NowPlaying,
    ): NowPlaying =
        if (report.title != known.title) {
            report.copy(play = known.play)
        } else {
            report.copy(
                artists = report.artists.ifEmpty { known.artists },
                album = report.album ?: known.album,
                mediaId = report.mediaId ?: known.mediaId,
                duration = report.duration ?: known.duration,
                year = report.year ?: known.year,
                play = known.play,
            )
        }

    /**
     * Whether [track] is [last] playing on: the same track ([same]) with the same title. A new
     * length, album or artists of it plays on; a title changing under one media id (a stream's next
     * song) does not. The session history's rule for a row that continues
     * (`HistoryRecorder.sameAsLast`) and the pipeline's for its insight (`sameInsightTrack`).
     */
    fun playsOn(
        last: NowPlaying,
        track: NowPlaying,
    ): Boolean = same(last, track) && last.title == track.title

    private fun differ(
        a: String?,
        b: String?,
    ): Boolean = a != null && b != null && a != b
}
