package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.language.Language
import app.sleevesight.core.language.Languages
import app.sleevesight.core.language.TrackLanguage
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * A run's search in the track's language (#902; `design/architecture.md`, *Search in the track's
 * language*), made once every English source has landed and before the evidence is assembled, so the
 * insight is written once, from all of it: one paid call, not one before the search and a rewrite
 * after. The song's, the artist's and the album's insights and the genre reasons read the same
 * evidence ([languageDrafts]).
 *
 * Under *Search in the track's language* ([Settings.trackLanguageSearch]): *Off*, none; *If needed*,
 * only while the English sources give fewer than [PromptEvidence.ENOUGH_OF_ITS_OWN] lines about the
 * track itself (the song, its original, the web: [PromptEvidence.TRACK_SCOPES]), the count at which
 * the prompt already leaves the artist's lines out; *Always*, whenever a language is found. Each
 * lookup obeys its own source's switch: the language's Wikipedia, with Wikidata's names in it, that
 * of Wikipedia and Wikidata, and the web search in it that of Exa web search.
 *
 * The language is [TrackLanguage]'s, from the track's names and what MusicBrainz and Wikidata
 * record, each asked only when the hints before it have not decided; English, or none, searches
 * nothing. A track whose English sources all came from the retrieval cache has no resolution, which
 * the recorded hints and the lookups need: it is made then, MusicBrainz switched on (its answers
 * held in memory for half an hour in the app, `RememberedMusicBrainz`).
 *
 * Each lookup is kept in the retrieval cache under the track's key and the language
 * ([PipelineCache.result]), as the English ones are: not when unreachable or provisional, nor when
 * the resolution it was made with could not be made.
 *
 * What it ended with goes in [Gathered.inLanguage], so a later run of the track treats its lookups as
 * the English ones: one asking again (#192, a *Retry*) keeps those that found something and asks the
 * others again ([Gathered.languageAsked]); one looking again (#617) shows no less than the run
 * before found in the same language ([LookAgain.keptInLanguage]). Either searches whenever the run
 * before did, but under *Off*, in the language it named when the hints now name none.
 */
internal class InLanguage(
    private val parts: PipelineParts,
    private val input: RunInput,
    private val control: RunControl,
    private val guard: CacheGuard,
    private val sink: RunSink,
    private val state: () -> TrackState,
) {
    private val settings = input.settings
    private val track = input.track
    private val key = track.lookupKey
    private val looksAgain = input.lookAgain != null

    // What the run before found in the track's language, for a run asking again (#192) or looking
    // again (#617): its language, and each lookup's state.
    private val earlier: InLanguageState? = (input.lookAgain?.before ?: input.again)?.inLanguage

    /** Searches in the track's language, when the setting and the switches have it searched. */
    suspend fun search() {
        val search = parts.languageSearch ?: return
        val adapters = listOfNotNull(search.wikipedia, search.web).filter { settings.isEnabled(it.id) }
        if (adapters.isEmpty() || !wanted(state())) return
        control.await(RunStep.LOOKUPS)
        try {
            searchIn(search, adapters)
        } finally {
            control.ended(RunStep.LOOKUPS)
        }
    }

    // Whether the setting has the track searched in its language, given what [before] found in English;
    // always, but under *Off*, for a run asking or looking again after one that searched, so it finds
    // no less than that run did.
    private fun wanted(before: TrackState): Boolean =
        when (settings.trackLanguageSearch) {
            TrackLanguageSearch.OFF -> false
            TrackLanguageSearch.IF_NEEDED ->
                earlier != null ||
                    ownLines(before, settings) < PromptEvidence.ENOUGH_OF_ITS_OWN
            TrackLanguageSearch.ALWAYS -> true
        }

    // The language, then each of [adapters]' lookups in it: from the cache, else made, sharing one
    // request for Wikidata's names.
    private suspend fun searchIn(
        search: LanguageSearch,
        adapters: List<LanguageAdapter>,
    ) {
        val before = state()
        val (resolution, made) = before.resolution?.let { it to (it.outcome != Outcome.Unreachable) } ?: resolve()
        // A run asking or looking again keeps the language the run before named, when its hints now name none.
        val language = language(search, before, resolution) ?: earlier?.language?.let(Languages::ofCode)
        sink.emit(PipelineEvent.LanguageFound(key, language?.code))
        if (language == null) return
        val pending = adapters.associate { it.id to SourceState.Pending }
        sink.update { it.copy(inLanguage = InLanguageState(language.code, pending)) }
        val query = LanguageQuery(track, resolution, language)
        val kept = keptBefore(adapters, language)
        kept.forEach { (adapter, state) -> land(adapter, language, state) }
        val misses = adapters.filter { it !in kept && (looksAgain || !fromCache(it, language)) }
        if (misses.isEmpty()) return
        val items =
            if (settings.isEnabled(SourceId.WIKIDATA)) {
                attempt(SourceId.WIKIDATA) { search.items(query) } ?: LanguageItems(unreachable = true)
            } else {
                LanguageItems.NONE
            }
        coroutineScope {
            for (adapter in misses) launch { lookup(adapter, query, items, made) }
        }
    }

    // What a run asking again (#192) keeps of the run before's lookups in [language], as it does the
    // English ones (`TrackRun`): every lookup that found something, but those it asks again
    // ([Gathered.languageAsked]: unreachable, or provisional).
    private fun keptBefore(
        adapters: List<LanguageAdapter>,
        language: Language,
    ): Map<LanguageAdapter, SourceState> {
        val again = input.again
        val before =
            again
                ?.inLanguage
                ?.takeIf { it.language == language.code }
                ?.sources
                .orEmpty()
        return adapters
            .filter { again != null && it.id !in again.languageAsked }
            .mapNotNull { adapter -> before[adapter.id]?.takeIf(TrackRun::keeps)?.let { adapter to it } }
            .toMap()
    }

    // The resolution, for a track whose sources all came from the cache: made now, if MusicBrainz is on.
    private suspend fun resolve(): Pair<Resolution, Boolean> {
        if (!settings.isEnabled(SourceId.MUSICBRAINZ)) return Resolution.NONE to false
        val resolved = attempt(SourceId.MUSICBRAINZ) { parts.resolver.resolve(track, input.nowPlaying.duration) }
        return (resolved ?: Resolution.NONE) to (resolved != null && resolved.outcome != Outcome.Unreachable)
    }

    // The language, its recorded hints asked in turn (`TrackLanguage.find`): MusicBrainz's lyrics
    // language (the resolution's, else MusicBrainz's kept result's), Wikidata's (Wikipedia's switch),
    // the releases' (MusicBrainz's switch).
    private suspend fun language(
        search: LanguageSearch,
        before: TrackState,
        resolution: Resolution,
    ): Language? {
        val kept = (before.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.lyrics
        val lyrics = resolution.lyrics.languages.ifEmpty { kept?.languages.orEmpty() }
        val musicBrainz = settings.isEnabled(SourceId.MUSICBRAINZ)
        return TrackLanguage.find(
            listOf(track.title) + track.artists,
            listOf(
                { if (musicBrainz) TrackLanguage.ofIso3(lyrics) else emptyList() },
                {
                    if (settings.isEnabled(SourceId.WIKIDATA)) {
                        found(attempt(SourceId.WIKIDATA) { search.workLanguages(resolution) })
                            ?.let(TrackLanguage::ofItems)
                            .orEmpty()
                    } else {
                        emptyList()
                    }
                },
                {
                    if (musicBrainz && resolution.resolved) {
                        found(attempt(SourceId.MUSICBRAINZ) { search.releaseLanguages(resolution) })
                            ?.let(TrackLanguage::ofIso3)
                            .orEmpty()
                    } else {
                        emptyList()
                    }
                },
            ),
        )
    }

    private suspend fun fromCache(
        adapter: LanguageAdapter,
        language: Language,
    ): Boolean {
        val hit =
            guard.attempt("read ${adapter.id.tag} in ${language.code}") {
                result(adapter, language.code, track, input.nowPlaying.duration)
            } ?: return false
        land(adapter, language, SourceState.Done(hit, cached = true))
        return true
    }

    private suspend fun lookup(
        adapter: LanguageAdapter,
        query: LanguageQuery,
        items: LanguageItems,
        made: Boolean,
    ) {
        val landed =
            try {
                SourceState.Done(adapter.lookup(query, items), cached = false)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                SourceState.Failed(TrackRun.nameOf(e))
            }
        // A look again (#617) shows what it found, or what was found before when it found nothing new;
        // one shown with what was found before is not kept, as for the English lookups.
        val shown = input.lookAgain?.keptInLanguage(query.language.code, adapter.id, landed) ?: landed
        land(adapter, query.language, shown)
        val result = (landed as? SourceState.Done)?.result ?: return
        val keeps = made && !result.provisional && result.outcome != Outcome.Unreachable
        if (shown === landed && keeps) {
            guard.attempt("write ${adapter.id.tag} in ${query.language.code}") {
                keep(adapter, query.language.code, track, input.nowPlaying.duration, result)
            }
        }
    }

    private fun land(
        adapter: LanguageAdapter,
        language: Language,
        landed: SourceState,
    ) {
        when (landed) {
            is SourceState.Done -> {
                val result = landed.result
                sink.emit(
                    PipelineEvent.LanguageLanded(
                        key = key,
                        language = language.code,
                        source = adapter.id,
                        outcome = result.outcome,
                        evidence = result.evidence.size,
                        latency = result.latency,
                        cached = landed.cached,
                    ),
                )
            }

            is SourceState.Failed -> sink.emit(PipelineEvent.SourceFailed(key, adapter.id, landed.reason))
            SourceState.Pending -> Unit
        }
        sink.update { current ->
            val searched = current.inLanguage ?: return@update current
            current.copy(inLanguage = searched.copy(sources = searched.sources + (adapter.id to landed)))
        }
    }

    // Never throws but for cancellation: a hint or lookup of [source] that fails (a bug, not the
    // network, which a `Lookup` reports) is none, its type logged.
    private suspend fun <T> attempt(
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

    internal companion object {
        /**
         * The lines about the track itself (the song, its original, the web) of [state]'s English
         * sources switched on.
         */
        fun ownLines(
            state: TrackState,
            settings: Settings,
        ): Int =
            state.sources.values
                .mapNotNull { (it as? SourceState.Done)?.result }
                .flatMap { it.evidence }
                .count { settings.isEnabled(it.source) && it.scope in PromptEvidence.TRACK_SCOPES }
    }
}

private fun <T> found(lookup: Lookup<T>?): T? = (lookup as? Lookup.Found)?.value

/**
 * The lines the search in the track's language found (#902, [TrackState.inLanguage]), to join the
 * English ones after them: each lookup's evidence, less a line whose page an English line of its
 * source already quotes (a web search in the language that found an English page found before).
 */
internal fun TrackState.languageDrafts(): List<EvidenceDraft> {
    val searched = inLanguage ?: return emptyList()
    val english =
        sources.values
            .mapNotNull { (it as? SourceState.Done)?.result }
            .flatMap { it.evidence }
            .mapNotNullTo(mutableSetOf()) { line -> line.url?.let { line.source to it } }
    return searched.sources.values
        .mapNotNull { (it as? SourceState.Done)?.result }
        .flatMap { it.evidence }
        .filter { line -> line.url == null || (line.source to line.url) !in english }
}
