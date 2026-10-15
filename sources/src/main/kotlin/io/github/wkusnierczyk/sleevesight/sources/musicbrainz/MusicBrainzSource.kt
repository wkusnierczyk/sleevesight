package io.github.wkusnierczyk.sleevesight.sources.musicbrainz

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.resolution.MusicBrainzEvidence
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * MusicBrainz as a source of evidence. Its lookup is the resolution itself
 * ([io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver], which the pipeline runs
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
