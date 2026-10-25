package app.sleevesight.app.report

import android.os.SystemClock
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import kotlin.time.Clock

/**
 * Where a tester's report (#452) takes what the app knows: what the pipeline publishes ([view]),
 * what the session layer reports of the player ([playing], [playback]), and the [diagnostics]: its
 * events kept in memory, what skip detection has learned, and the track changes recorded, read
 * only when the tester turns the history on. Never the key store: nothing here can read a key.
 *
 * [clock], [zone] and [elapsedRealtime] are the time of the tap, its time zone and the clock the
 * playback's position is reported on.
 */
@Suppress("LongParameterList") // What the report reads, each published on its own, and the clocks.
class TesterReports(
    private val build: AppBuild,
    private val view: StateFlow<PipelineView>,
    private val playing: StateFlow<NowPlaying?>,
    private val playback: StateFlow<PlaybackInfo>,
    private val diagnostics: Diagnostics,
    private val clock: Clock = Clock.System,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    /** What the app knows now, with [settings] as last read (null before the first read). */
    fun snapshot(settings: Settings?): TesterSnapshot {
        val playback = playback.value
        return TesterSnapshot(
            at = clock.now(),
            zone = zone(),
            build = build,
            settings = settings,
            playing = playing.value,
            playback = playback,
            position = playback.positionAt(elapsedRealtime()),
            view = view.value,
            events = diagnostics.log.entries.value,
            learned = diagnostics.skipLearning?.learnedCrossfades?.value,
        )
    }

    /** The history part of a report of [snapshot], as of its tap ([TesterReport.history]). */
    suspend fun history(snapshot: TesterSnapshot): String = TesterReport.history(diagnostics::changes, snapshot.at)
}
