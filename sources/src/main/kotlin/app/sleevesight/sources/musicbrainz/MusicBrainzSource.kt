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
 */
class MusicBrainzSource(
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : SourceAdapter {
    override val id: SourceId = SourceId.MUSICBRAINZ

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        val resolved = resolution.await()
        return SourceResult(resolved.outcome, MusicBrainzEvidence.lines(resolved, clock.now()), start.elapsedNow())
    }
}
