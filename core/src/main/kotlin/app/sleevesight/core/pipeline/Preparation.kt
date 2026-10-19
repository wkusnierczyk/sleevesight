package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Preparing the next track ahead, *Evidence* (D13; `design/architecture.md`, *Preparing the next
 * track*), from the player's queue item as the app reads it (`QueueEntry`: the title, the artists
 * and the media id), with no album and no length: Spotify's items carry neither, and Castbox's
 * carry both in their extras, which `QueueEntry` does not read (#172). What it looks up is not
 * kept under the track's own keys, which hold the length the playing track will report; it fills
 * what the playing track's run then finds by name or id, so that run sends few requests or none
 * to MusicBrainz, the slow part (R3):
 *
 * - the resolution, by name only, whose MusicBrainz answers the resolver's lookup keeps for a while
 *   (`RememberedMusicBrainz`, in the app); the playing track's run resolves again, with its length;
 * - the adapters that prepare ahead ([SourceAdapter.preparesAhead]): MusicBrainz's lines, which
 *   send nothing and name the resolved artists;
 * - MusicBrainz's genre labels for those artists, kept by `MusicBrainzLabels`.
 *
 * Nothing is published, nothing goes to the retrieval or synthesis cache, and no insight is
 * written: a card written from a queue item would be kept under a key the playing track never
 * reads (its evidence lacks the album's line), so the track would be paid for twice (#172: Spotify's
 * queue items carry neither album nor length). Nothing is prepared with MusicBrainz switched off:
 * its lookup is what is slow.
 * Each step that sends requests starts once [control] grants it, as a run's do. A resolution that
 * ends unreachable for a passing failure is tried once more, [RETRY_WAIT] later (#191): the
 * lookups step ends, and is asked for again after the wait, so the second try, like the first,
 * waits until requests are allowed. A new run cancels the preparation, waiting or not.
 */
internal class Preparation(
    private val parts: PipelineParts,
    private val time: PipelineTime,
    private val settings: Settings,
    private val track: NormalizedTrack,
    private val control: RunControl,
    private val onEvent: (PipelineEvent) -> Unit,
) {
    private val key = track.lookupKey

    suspend fun run() {
        if (!settings.isEnabled(SourceId.MUSICBRAINZ) || track.title.isBlank() || track.primary.isBlank()) return
        val results = lookUp()
        val artists = results.firstNotNullOfOrNull { it.labelArtists.takeIf(List<String>::isNotEmpty) }.orEmpty()
        val labels = parts.musicBrainzLabels?.takeIf { settings.givesLabels(LabelSource.MUSICBRAINZ) }
        if (labels == null || artists.isEmpty()) return
        control.await(RunStep.LABELS)
        try {
            labels.labels(artists)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            onEvent(PipelineEvent.SourceFailed(key, SourceId.MUSICBRAINZ, "labels: ${TrackRun.nameOf(e)}"))
        } finally {
            control.ended(RunStep.LABELS)
        }
    }

    // The resolution by name, then the adapters that prepare ahead, given it; each try in a
    // lookups step of its own. Null from the first step: to be tried again.
    private suspend fun lookUp(): List<SourceResult> {
        val first =
            lookups {
                val resolution = resolve(again = false)
                if (resolution != null && triesAgain(resolution)) null else adapters(resolution)
            }
        if (first != null) return first
        delay(RETRY_WAIT)
        return lookups { adapters(resolve(again = true)) }
    }

    // [block] in a lookups step: started once granted, its end reported once it was.
    private suspend fun <T> lookups(block: suspend () -> T): T {
        control.await(RunStep.LOOKUPS)
        try {
            return block()
        } finally {
            control.ended(RunStep.LOOKUPS)
        }
    }

    // The adapters that prepare ahead, given [resolution]; none if the resolver failed.
    private suspend fun adapters(resolution: Resolution?): List<SourceResult> {
        if (resolution == null) return emptyList()
        val adapters = parts.adapters.filter { settings.isEnabled(it.id) && it.preparesAhead }
        val resolved = CompletableDeferred(resolution)
        return coroutineScope {
            adapters.map { adapter -> async { lookup(adapter, resolved) } }.awaitAll().filterNotNull()
        }
    }

    // By name only: a queue item has no length. Null if the resolver failed (a bug: it maps its
    // failures to outcomes).
    private suspend fun resolve(again: Boolean): Resolution? {
        val started = time.timeSource.markNow()
        return try {
            parts.resolver.resolve(track, null).also {
                onEvent(PipelineEvent.Prepared(key, it.outcome, started.elapsedNow(), it.failure, again))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            onEvent(PipelineEvent.SourceFailed(key, SourceId.MUSICBRAINZ, TrackRun.nameOf(e)))
            null
        }
    }

    private suspend fun lookup(
        adapter: SourceAdapter,
        resolution: CompletableDeferred<Resolution>,
    ): SourceResult? =
        try {
            adapter.lookup(track, resolution)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A bug in one adapter must not take the others down; its type is logged.
            onEvent(PipelineEvent.SourceFailed(key, adapter.id, TrackRun.nameOf(e)))
            null
        }

    companion object {
        /**
         * How long a preparation whose resolution ended unreachable for a passing failure
         * ([PASSING]) waits before its one more try (#191). MusicBrainz can refuse requests (503)
         * for a moment when the pool it shares among its clients runs out, however slowly one
         * client sends, and the client's own retries end within about 2 s at its one request a
         * second; a while later, it answers again. A preparation runs minutes before its track
         * plays, and the user never waits for it, so it can wait this long, and one more try
         * bounds what it sends. A constant, not a setting: nothing in it is the user's to choose.
         */
        val RETRY_WAIT: Duration = 15.seconds

        /**
         * The failures tried again: those `:net` retries itself (`KtorNetClient.retryWait`), as
         * `Lookup.Failed` names them (`JsonAccess.describe` in `:sources`, the `NetError`'s type).
         * Any other, such as an unexpected status (`status 400`) or an answer that cannot be read
         * (`Parse`), would fail the same way again.
         */
        val PASSING: Set<String> = setOf("RateLimited", "Server", "Offline")

        // Whether [resolution] ended unreachable for a passing failure.
        internal fun triesAgain(resolution: Resolution): Boolean =
            resolution.outcome == Outcome.Unreachable && resolution.failure in PASSING
    }
}
