package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.LastFmLabels
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * One run's genre labels (#84, #439): those of the label sources [settings] switch on, a row per
 * level ([combineGenres]). With the sources' results come Last.fm's artist labels and MusicBrainz's
 * recording labels, from the tags its resolution brought. After the evidence is assembled, never
 * part of it, so the insight neither waits for them nor sees them, come the rest ([addLater]):
 * MusicBrainz's artist labels, from the tags of the artists its result names ([musicBrainz]), and
 * Last.fm's track and album labels ([lastFm], with the user's key).
 */
internal class TrackLabels(
    private val musicBrainz: MusicBrainzLabels?,
    private val lastFm: LastFmLabels?,
    private val settings: Settings,
    private val key: String,
    private val sink: RunSink,
    private val fold: (String) -> String,
) {
    /** The labels asked for after the evidence, by source, as they land. */
    private val later = MutableStateFlow<Map<LabelSource, List<GenreLabel>>>(emptyMap())

    /** The genres for [current]'s results, with those asked for after the evidence that are in. */
    fun of(current: TrackState): TrackGenres {
        val landed = later.value
        return combineGenres(
            LabelSource.entries.filter(settings::givesLabels).associateWith { source ->
                val result = current.done(source.source)
                val given =
                    when (source) {
                        LabelSource.LASTFM -> result?.genres.orEmpty()
                        LabelSource.MUSICBRAINZ ->
                            result?.recordingTags?.let { musicBrainz?.recordingLabels(it) }.orEmpty()
                    }
                credited(given + landed[source].orEmpty(), current.normalized.artists)
            },
            current.normalized.artists,
        )
    }

    // Each artist's label under the name this track credits, by its folded key ([fold]): a result
    // served from the cache was kept for a spelling of the key's ("Sigur Rós" for "Sigur Ros").
    private fun credited(
        labels: List<GenreLabel>,
        artists: List<String>,
    ): List<GenreLabel> {
        val byKey = artists.associateBy(fold)
        return labels.map { label ->
            label.artist
                ?.let { byKey[fold(it)] }
                ?.takeIf { it != label.artist }
                ?.let { label.copy(artist = it) } ?: label
        }
    }

    /**
     * Asks for the labels that come after the evidence, each source's at once, and adds each as it
     * lands: MusicBrainz's for the artists [current]'s MusicBrainz result names; Last.fm's for the
     * track and its album, by the primary artist, once Last.fm's result shows an artist and has not
     * rejected the primary (an artist it rejected may be another's of the name, whose track this is
     * not).
     */
    suspend fun addLater(current: TrackState) =
        coroutineScope {
            val artists = current.done(SourceId.MUSICBRAINZ)?.labelArtists.orEmpty()
            val fromMusicBrainz =
                musicBrainz?.takeIf {
                    artists.isNotEmpty() &&
                        settings.givesLabels(LabelSource.MUSICBRAINZ)
                }
            if (fromMusicBrainz != null) {
                launch { add(LabelSource.MUSICBRAINZ) { fromMusicBrainz.labels(artists) } }
            }
            val track = current.normalized
            val fromLastFmResult = current.done(SourceId.LASTFM)
            val shown =
                fromLastFmResult?.outcome == Outcome.ArtistArticle &&
                    fromLastFmResult.rejections.none {
                        it.scope == Scope.ARTIST &&
                            fold(it.subject) == fold(track.primary)
                    }
            val fromLastFm = lastFm?.takeIf { shown && settings.givesLabels(LabelSource.LASTFM) }
            if (fromLastFm != null && track.primary.isNotBlank()) {
                launch { add(LabelSource.LASTFM) { fromLastFm.trackLabels(track.primary, track.title) } }
                current.nowPlaying.album
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { album ->
                        launch { add(LabelSource.LASTFM) { fromLastFm.albumLabels(track.primary, album) } }
                    }
            }
        }

    // [ask]'s labels, added to [source]'s and published; a failure gives none, and is logged.
    private suspend fun add(
        source: LabelSource,
        ask: suspend () -> List<GenreLabel>,
    ) {
        val found =
            try {
                ask()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A bug in the labels must not take the insight down; its type is logged.
                sink.emit(PipelineEvent.SourceFailed(key, source.source, "labels: ${TrackRun.nameOf(e)}"))
                return
            }
        if (found.isEmpty()) return
        later.update { it + (source to it[source].orEmpty() + found) }
        sink.update { it.copy(genres = of(it)) }
    }

    private fun TrackState.done(source: SourceId) = (sources[source] as? SourceState.Done)?.result
}
