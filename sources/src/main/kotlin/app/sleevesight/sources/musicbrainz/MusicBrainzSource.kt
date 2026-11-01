package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.MusicBrainzEvidence
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WITHOUT_GENRES_VERSION
import kotlinx.coroutines.Deferred
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * MusicBrainz as a source of evidence. Its lookup is the resolution itself
 * ([app.sleevesight.core.resolution.RecordingResolver], which the pipeline runs
 * once per track and hands every adapter): this adapter waits for it, takes its outcome, and
 * templates its lines ([MusicBrainzEvidence]). Its latency is the wait.
 *
 * Its result also names the resolved artists ([SourceResult.labelArtists]; for a joint credit, its
 * artists, [Resolution.jointArtists]), whose tags give
 * MusicBrainz's genre labels (#84), and carries the recording's tags the resolution brought
 * ([SourceResult.recordingTags], #439), for the track's; it asks for no tags itself. The pipeline
 * asks for the artists', after the evidence, so the insight never waits for them; no tag reaches a
 * line. It also keeps the
 * date of the first release ([SourceResult.firstReleased]) and the dates of the releases' titles
 * ([SourceResult.releaseDates], #556), the first release itself ([SourceResult.firstRelease]) and a
 * cover's original with its release ([SourceResult.original], #724), for a track later served from
 * the cache, and, for the accepted recording, what MusicBrainz says of its lyrics
 * ([SourceResult.lyrics], #728). A resolution a failed lookup left short ([Resolution.provisional]:
 * of the original's release, or of the lyrics links) gives a [SourceResult.provisional] result:
 * shown, not kept, so a later run asks again.
 */
class MusicBrainzSource(
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val genres: Boolean = true,
) : SourceAdapter {
    override val id: SourceId = SourceId.MUSICBRAINZ

    /**
     * 2: the result names the resolved artists, for the genre labels (#84). 3: and the date of the
     * first release, for the home screen's year (#52). 4: the artists by name too, and the
     * recording's tags, for the genre rows (#439). 5: and the dates of the releases' titles, for the
     * year after the album (#556). 6: a joint credit resolves (#661), so a track kept as *nothing found*
     * or *rejected* by the rules before is asked again, and its artists give the genre labels. 7: and
     * the work's lyrics links, languages and whether the recording is instrumental, for *Lyrics* (#728),
     * so a track kept before is asked again rather than shown as having no lyrics page, and the first
     * release's title and type and the original's release, for *Dates* (#724). 8: and the votes on
     * the recording's tags, by which a track's label leads its folded genres only with two (#799), so
     * a track kept before is asked again rather than shown under the album's labels. Without
     * [genres] (*Show genres* off, #444), it keeps no tags, and its results are kept apart ([WITHOUT_GENRES_VERSION]).
     */
    override val extractionVersion: Int = EXTRACTION_VERSION + if (genres) 0 else WITHOUT_GENRES_VERSION

    /** It sends no request of its own, and its result names the artists the labels ask about (D13). */
    override val preparesAhead: Boolean = true

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        val resolved = resolution.await()
        return SourceResult(
            outcome = resolved.outcome,
            evidence = MusicBrainzEvidence.lines(resolved, clock.now()),
            latency = start.elapsedNow(),
            // A joint credit's artists too (#661), whose track artist has no id of its own.
            labelArtists = (resolved.artists.filter { it.mbid != null } + resolved.jointArtists).distinctBy { it.mbid },
            recordingTags =
                resolved.recordingMbid?.takeIf { genres && resolved.recordingTags.isNotEmpty() }?.let {
                    RecordingTags(it, resolved.recordingTags, resolved.recordingTagVotes)
                },
            firstReleased = resolved.firstReleaseDate,
            releaseDates = resolved.releaseDates,
            firstRelease = resolved.firstRelease,
            original = resolved.original,
            // Only for an accepted recording: without one, there is no song to link the lyrics of.
            lyrics = resolved.lyrics.takeIf { resolved.resolved },
            provisional = resolved.provisional,
            // No recording and no artist: a result that names an artist, or rejects a recording by
            // another one, is about the artists (#420).
            trackOnly = resolved.outcome == Outcome.None && resolved.artists.isEmpty() && resolved.artistCredit == null,
        )
    }
}

private const val EXTRACTION_VERSION = 8
