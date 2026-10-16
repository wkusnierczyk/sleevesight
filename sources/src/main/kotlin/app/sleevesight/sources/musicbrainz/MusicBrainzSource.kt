package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.MusicBrainzEvidence
import app.sleevesight.core.settings.Settings
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
 * With [labels], and while MusicBrainz gives genre labels ([Settings.givesLabels], #84), it then
 * asks for the resolved artists' tags, one request each, for the result's genre labels; never for
 * its lines, so the tags never reach a prompt. A failed tag request leaves the result
 * [SourceResult.provisional], so a later lookup gets the labels. Without [labels] (the CLI), no tag
 * is asked for.
 */
class MusicBrainzSource(
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val labels: MusicBrainzLabels? = null,
) : SourceAdapter {
    override val id: SourceId = SourceId.MUSICBRAINZ

    /** 2 for a result with the artists' genre labels, 1 for one without. */
    override val extractionVersion: Int get() = if (labels != null) WITH_LABELS else 1

    override fun configuredFor(settings: Settings): SourceAdapter =
        if (labels != null && !settings.givesLabels(LabelSource.MUSICBRAINZ)) {
            MusicBrainzSource(clock, timeSource, labels = null)
        } else {
            this
        }

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult {
        val start = timeSource.markNow()
        val resolved = resolution.await()
        val lines = MusicBrainzEvidence.lines(resolved, clock.now())
        val found = labels?.labels(resolved)
        return SourceResult(
            outcome = resolved.outcome,
            evidence = lines,
            latency = start.elapsedNow(),
            genres = found?.labels.orEmpty(),
            provisional = found?.complete == false,
        )
    }

    private companion object {
        const val WITH_LABELS = 2
    }
}
