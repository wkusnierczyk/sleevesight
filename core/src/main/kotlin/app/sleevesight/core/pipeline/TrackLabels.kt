package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.LabelLookup
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

    /** A request after the evidence: [source]'s, which [ask] makes. */
    class Later(
        val source: LabelSource,
        val ask: suspend () -> LabelLookup,
    )

    // The requests after the evidence not yet answered, and the sources one of whose failed (#772).
    private val pending = MutableStateFlow<Map<LabelSource, Int>>(emptyMap())
    private val failed = MutableStateFlow<Set<LabelSource>>(emptySet())

    /**
     * The labels that come after the evidence, for [current], counted as asked for from now (#772,
     * [states], which the run publishes with the assembled genres), before the run may start them:
     * MusicBrainz's for the
     * artists [current]'s MusicBrainz result names; Last.fm's for the track and its album, by the
     * primary artist, once Last.fm's result shows an artist and has not rejected the primary (an
     * artist it rejected may be another's of the name, whose track this is not). [addLater] asks
     * them, and [settle] ends what is left unanswered.
     */
    fun later(current: TrackState): List<Later> {
        val asks = mutableListOf<Later>()
        val artists = current.done(SourceId.MUSICBRAINZ)?.labelArtists.orEmpty()
        val fromMusicBrainz =
            musicBrainz?.takeIf {
                artists.isNotEmpty() &&
                    settings.givesLabels(LabelSource.MUSICBRAINZ)
            }
        if (fromMusicBrainz != null) asks += Later(LabelSource.MUSICBRAINZ) { fromMusicBrainz.lookUp(artists) }
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
            asks += Later(LabelSource.LASTFM) { fromLastFm.trackLookup(track.primary, track.title) }
            current.nowPlaying.album
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { album -> asks += Later(LabelSource.LASTFM) { fromLastFm.albumLookup(track.primary, album) } }
        }
        pending.value = asks.groupingBy { it.source }.eachCount()
        return asks
    }

    /** Asks for each of [asks] at once, and adds each one's labels as they land. */
    suspend fun addLater(asks: List<Later>) =
        coroutineScope {
            for (later in asks) launch { add(later.source, later.ask) }
        }

    /**
     * Ends the requests after the evidence still unanswered (the run cancelled, or never granted
     * them): each such source done, so a panel never says it is still looking for a run that has
     * stopped asking. Published while the run is the current one only, as every state is.
     */
    fun settle() {
        if (pending.value.values.none { it > 0 }) return
        pending.value = emptyMap()
        sink.update { it.copy(laterLabels = states()) }
    }

    /**
     * How each source asked after the evidence stands ([TrackState.laterLabels]): asking while a
     * request is unanswered, else failed when one failed, else done.
     */
    fun states(): Map<LabelSource, LaterLabels> =
        (pending.value.keys + failed.value).associateWith { source ->
            when {
                (pending.value[source] ?: 0) > 0 -> LaterLabels.ASKING
                source in failed.value -> LaterLabels.FAILED
                else -> LaterLabels.DONE
            }
        }

    // [ask]'s labels, added to [source]'s and published, with how [source]'s requests stand; a
    // failure gives none, is logged, and marks the source failed.
    private suspend fun add(
        source: LabelSource,
        ask: suspend () -> LabelLookup,
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
                LabelLookup(emptyList(), failed = true)
            }
        if (found.labels.isNotEmpty()) later.update { it + (source to it[source].orEmpty() + found.labels) }
        if (found.failed) failed.update { it + source }
        pending.update { it + (source to ((it[source] ?: 1) - 1).coerceAtLeast(0)) }
        sink.update { it.copy(genres = of(it), laterLabels = states()) }
    }

    private fun TrackState.done(source: SourceId) = (sources[source] as? SourceState.Done)?.result
}
