package app.sleevesight.core.pipeline

import app.sleevesight.core.language.Language
import app.sleevesight.core.language.Languages
import app.sleevesight.core.language.TrackLanguage
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.Resolution
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which language a run searches in (#902, [InLanguage]), and the resolution its lookups need, each
 * asked for only as needed.
 *
 * The language is [TrackLanguage]'s. The hints that need no request come first: the track's names'
 * script, and MusicBrainz's lyrics language as the run's resolution has it or, for a track whose
 * sources came from the retrieval cache, as MusicBrainz's kept result has it. Wikidata's language of
 * the work and the releases' need the resolution, which is made only then (MusicBrainz switched on),
 * for a track whose sources all came from the cache. The decision (a language, English, or none) is
 * kept per track in the retrieval cache ([PipelineCache.trackLanguage]) once every hint it asked
 * answered, so a replay asks nothing to decide it again; a look again (#617) decides afresh.
 */
internal class LanguageDecision(
    private val parts: PipelineParts,
    private val input: RunInput,
    private val guard: CacheGuard,
    private val sink: RunSink,
    private val before: TrackState,
) {
    private val settings = input.settings
    private val track = input.track
    private var resolved: Pair<Resolution, Boolean>? = null

    // Whether every hint the decision reached was asked and answered, so it may be kept: not when a
    // source a hint needs was switched off, could not be asked, or answered only in part.
    private var answered = true

    /**
     * The resolution the run's lookups are made with, and whether it was made (not unreachable, nor
     * MusicBrainz off): the run's own, else made now, once.
     */
    suspend fun resolution(): Pair<Resolution, Boolean> =
        resolved ?: (own()?.let { it to (it.outcome != Outcome.Unreachable) } ?: resolve()).also {
            resolved = it
        }

    // The run's own resolution; one unreachable or provisional answers its hints only in part.
    private fun own(): Resolution? =
        before.resolution?.also { if (it.outcome == Outcome.Unreachable || it.provisional) answered = false }

    /**
     * The language the track is sung in: a language, [Languages.ENGLISH], or null when nothing names
     * one; from the cache unless the run looks again.
     */
    suspend fun language(search: LanguageSearch): Language? {
        val looksAgain = input.lookAgain != null
        val kept =
            if (looksAgain) {
                null
            } else {
                guard.attempt("read language") { trackLanguage(track, input.nowPlaying.duration) }
            }
        if (kept != null) return Languages.ofCode(kept)
        val decided = decide(search)
        if (answered) {
            guard.attempt(
                "write language",
            ) { keepTrackLanguage(track, input.nowPlaying.duration, decided?.code.orEmpty()) }
        }
        return decided
    }

    // The hints, in turn (`TrackLanguage.find`): MusicBrainz's lyrics language (the run's
    // resolution's, else the one MusicBrainz's kept result holds, a request only when it holds none),
    // Wikidata's (Wikipedia's switch) and the releases' majority (MusicBrainz's switch). A hint its
    // source's switch skips leaves the decision unkept, so switching it on decides again.
    private suspend fun decide(search: LanguageSearch): Language? {
        val kept = (before.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.lyrics
        val musicBrainz = settings.isEnabled(SourceId.MUSICBRAINZ)
        val names = listOf(track.title) + track.artists
        return TrackLanguage.find(
            names,
            listOf(
                {
                    if (musicBrainz) {
                        val lyrics = own()?.lyrics ?: kept ?: resolution().first.lyrics
                        TrackLanguage.ofIso3(lyrics.languages)
                    } else {
                        skipped()
                    }
                },
                {
                    if (settings.isEnabled(SourceId.WIKIDATA)) {
                        val resolution = resolution().first
                        answer(attempt(SourceId.WIKIDATA) { search.workLanguages(resolution) })
                            ?.let(TrackLanguage::ofItems)
                            .orEmpty()
                    } else {
                        skipped()
                    }
                },
                {
                    val resolution = if (musicBrainz) resolution().first else Resolution.NONE
                    when {
                        !musicBrainz -> skipped()
                        // No recording accepted: no release to record a language, a real answer.
                        !resolution.resolved -> emptyList()
                        else ->
                            answer(attempt(SourceId.MUSICBRAINZ) { search.releaseLanguages(resolution) })
                                ?.let { TrackLanguage.ofReleases(it, TrackLanguage.scriptOf(names)) }
                                .orEmpty()
                    }
                },
            ),
        )
    }

    // A hint its source's switch skips: none, and the decision not kept.
    private fun skipped(): List<Language> {
        answered = false
        return emptyList()
    }

    // The resolution, for a track whose sources all came from the cache: made now, if MusicBrainz is on.
    private suspend fun resolve(): Pair<Resolution, Boolean> {
        if (!settings.isEnabled(SourceId.MUSICBRAINZ)) return Resolution.NONE to false
        val made = attempt(SourceId.MUSICBRAINZ) { parts.resolver.resolve(track, input.nowPlaying.duration) }
        val reached = made != null && made.outcome != Outcome.Unreachable
        if (made == null || !reached || made.provisional) answered = false
        return (made ?: Resolution.NONE) to reached
    }

    // [lookup]'s value when found; none, and the decision not kept, when it failed.
    private fun <T> answer(lookup: Lookup<T>?): T? {
        if (lookup == null || lookup is Lookup.Failed) answered = false
        return (lookup as? Lookup.Found)?.value
    }

    // Never throws but for cancellation: a hint or the resolution that fails (a bug, not the
    // network, which a `Lookup` reports) is none, its type logged.
    private suspend fun <T> attempt(
        source: SourceId,
        block: suspend () -> T,
    ): T? = attempting(sink, track.lookupKey, source, block)
}

/**
 * [block], never throwing but for cancellation: one that throws (a bug, not the network) is null,
 * reported as [source]'s failure on the run's [key].
 */
internal suspend fun <T> attempting(
    sink: RunSink,
    key: String,
    source: SourceId,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        sink.emit(PipelineEvent.SourceFailed(key, source, TrackRun.nameOf(e)))
        null
    }
