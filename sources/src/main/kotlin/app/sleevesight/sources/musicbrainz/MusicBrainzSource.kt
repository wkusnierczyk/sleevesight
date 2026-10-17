package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.MusicBrainzEvidence
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * MusicBrainz as a source of evidence. Its lookup is the resolution itself
 * ([app.sleevesight.core.resolution.RecordingResolver], which the pipeline runs
 * once per track and hands every adapter): this adapter waits for it, takes its outcome, and
 * templates its lines ([MusicBrainzEvidence]). Its latency is the wait.
 *
 * Its result also names the resolved artists ([SourceResult.labelArtists]), whose tags give
 * MusicBrainz's genre labels (#84); it asks for no tags itself. The pipeline does, after the
 * evidence, so the insight never waits for them and the tags never reach a line. It also keeps the
 * date of the first release ([SourceResult.firstReleased]), for a track later served from the cache.
 */
class MusicBrainzSource(
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SourceAdapter {
    override val id: SourceId = SourceId.MUSICBRAINZ

    /**
     * 2: the result names the resolved artists, for the genre labels (#84). 3: and the date of the
     * first release, for the home screen's year (#52).
     */
    override val extractionVersion: Int = EXTRACTION_VERSION

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
            labelArtists = resolved.artists.mapNotNull { it.mbid }.distinct(),
            firstReleased = resolved.firstReleaseDate,
        )
    }
}

private const val EXTRACTION_VERSION = 3
