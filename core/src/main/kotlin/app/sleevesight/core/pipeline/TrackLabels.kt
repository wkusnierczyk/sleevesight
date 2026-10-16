package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.combineLabels
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import kotlin.coroutines.cancellation.CancellationException

/**
 * One run's genre labels (#84): those of the label sources [settings] switch on, combined. Last.fm's
 * come with its result; MusicBrainz's from the tags of the artists its result names, asked for by
 * [musicBrainz] after the evidence is assembled, never part of it, so the insight neither waits for
 * them nor sees them.
 */
internal class TrackLabels(
    private val musicBrainz: MusicBrainzLabels?,
    private val settings: Settings,
    private val key: String,
    private val sink: RunSink,
) {
    /** The labels for [current]'s results, with [fromMusicBrainz], MusicBrainz's once they are in. */
    fun of(
        current: TrackState,
        fromMusicBrainz: List<GenreLabel> = emptyList(),
    ): List<TrackLabel> =
        combineLabels(
            LabelSource.entries.filter(settings::givesLabels).associateWith { source ->
                val result = (current.sources[source.source] as? SourceState.Done)?.result
                result?.genres.orEmpty() + if (source == LabelSource.MUSICBRAINZ) fromMusicBrainz else emptyList()
            },
        )

    /** Asks for the tags of the artists [current]'s MusicBrainz result names, and adds their labels. */
    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see the safe call `labels?.labels` as suspending.
    suspend fun addMusicBrainz(current: TrackState) {
        val artists = (current.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.labelArtists.orEmpty()
        val labels = musicBrainz?.takeIf { artists.isNotEmpty() && settings.givesLabels(LabelSource.MUSICBRAINZ) }
        val found =
            try {
                labels?.labels(artists)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A bug in the labels must not take the insight down; its type is logged.
                sink.emit(PipelineEvent.SourceFailed(key, SourceId.MUSICBRAINZ, "labels: ${TrackRun.nameOf(e)}"))
                null
            }
        if (found != null) sink.update { it.copy(genres = of(it, found.labels)) }
    }
}
