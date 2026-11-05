package app.sleevesight.store

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.OtherCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The pipeline's [PipelineCache] over [caches]: the retrieval cache for source results, the
 * synthesis cache for written cards, with the track's retrieval key ([RetrievalCache.keyOf]) as a
 * card's entity key.
 * [Caches] makes blocking database calls, so each runs in [io]: the caller's
 * I/O dispatcher (`Dispatchers.IO` on the JVM), which `:core` and `:store` do not name, being
 * Multiplatform-ready.
 *
 * A result that says something about an artist ([RetrievalCodec.aboutArtists]) is kept only while
 * [keeping] is on (*Keep what sources said about each artist*, #420), in turn with its clear; while
 * it is off, that source is asked again for each track, as the artist-level cache is, and one kept
 * before is not read.
 *
 * Nothing found by work started before *Clear history* is kept after it ([epoch], #547).
 */
@Suppress("TooManyFunctions") // One for each read and keep of `PipelineCache`, and its keys.
class StorePipelineCache(
    private val caches: Caches,
    private val io: CoroutineContext,
    private val keeping: ArtistKeeping,
    private val epoch: ClearEpoch = ClearEpoch(),
) : PipelineCache {
    override suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? {
        keeping.retryClear()
        return withContext(io) { caches.retrieval.get(adapter, track, duration)?.result }
            // One kept before the switch was turned off, which a clear that failed left.
            ?.takeUnless { RetrievalCodec.aboutArtists(it) && !keeping.on }
    }

    override suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        val work = currentCoroutineContext()
        val put =
            suspend {
                epoch.ifCurrent(work) { withContext(io) { caches.retrieval.put(adapter, track, duration, result) } }
            }
        if (RetrievalCodec.aboutArtists(result)) keeping.ifOn { put() } else put()
    }

    override suspend fun result(
        adapter: LanguageAdapter,
        language: String,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult? {
        keeping.retryClear()
        return withContext(io) {
            caches.retrieval.get(adapter.id, languageKey(track, duration, language), adapter.extractionVersion)?.result
        }?.takeUnless { RetrievalCodec.aboutArtists(it) && !keeping.on }
    }

    override suspend fun keep(
        adapter: LanguageAdapter,
        language: String,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    ) {
        val work = currentCoroutineContext()
        val key = languageKey(track, duration, language)
        val put =
            suspend {
                epoch.ifCurrent(work) {
                    withContext(io) {
                        caches.retrieval.put(adapter.id, key, adapter.extractionVersion, result, adapter.consults)
                    }
                }
            }
        if (RetrievalCodec.aboutArtists(result)) keeping.ifOn { put() } else put()
    }

    override suspend fun trackLanguage(
        track: NormalizedTrack,
        duration: Duration?,
    ): String? =
        withContext(io) {
            caches.retrieval
                .get(DECIDED_BY, decisionKey(track, duration), DECISION_VERSION)
                ?.result
                ?.language
        }

    override suspend fun keepTrackLanguage(
        track: NormalizedTrack,
        duration: Duration?,
        code: String,
    ) {
        val work = currentCoroutineContext()
        // About the track alone, so kept whatever *Keep what sources said about each artist* says.
        val result = SourceResult(Outcome.None, emptyList(), Duration.ZERO, trackOnly = true, language = code)
        epoch.ifCurrent(work) {
            withContext(io) {
                caches.retrieval.put(
                    DECIDED_BY,
                    decisionKey(track, duration),
                    DECISION_VERSION,
                    result,
                    DECISION_CONSULTS,
                )
            }
        }
    }

    // The retrieval key of the language decided for a track (#902): the track's, then a line break
    // and a marker no lookup's key has.
    private fun decisionKey(
        track: NormalizedTrack,
        duration: Duration?,
    ): String = caches.retrieval.keyOf(track, duration) + "\n" + DECISION

    // The retrieval key of a lookup in the track's language (#902): the track's, then the language's
    // code past a line break, so it never meets the English lookup's entry (whose parts are
    // length-prefixed, so none starts with one) nor another language's.
    private fun languageKey(
        track: NormalizedTrack,
        duration: Duration?,
        language: String,
    ): String = caches.retrieval.keyOf(track, duration) + "\n" + LANGUAGE + language

    override suspend fun card(key: CardKey): String? = withContext(io) { caches.synthesis.get(key.toStore())?.text }

    override suspend fun keptCard(key: CardKey): KeptCard? =
        withContext(io) { caches.synthesis.get(key.toStore())?.let { KeptCard(it.text, it.writtenAt, it.rewritten) } }

    override suspend fun keptByOthers(key: CardKey): List<OtherCard> =
        withContext(io) {
            caches.synthesis.others(key.toStore()).map { (other, kept) ->
                OtherCard(
                    key.copy(provider = other.provider, model = other.model, effort = other.effort),
                    KeptCard(kept.text, kept.writtenAt, kept.rewritten),
                )
            }
        }

    override suspend fun keep(
        key: CardKey,
        text: String,
    ) {
        val work = currentCoroutineContext()
        epoch.ifCurrent(work) { withContext(io) { caches.synthesis.put(key.toStore(), text) } }
    }

    override suspend fun keep(
        key: CardKey,
        text: String,
        writtenAt: Instant,
    ) {
        val work = currentCoroutineContext()
        epoch.ifCurrent(work) { withContext(io) { caches.synthesis.put(key.toStore(), text, writtenAt) } }
    }

    override suspend fun keep(
        key: CardKey,
        text: String,
        writtenAt: Instant,
        rewritten: Set<RewritePart>,
    ) {
        val work = currentCoroutineContext()
        epoch.ifCurrent(work) { withContext(io) { caches.synthesis.put(key.toStore(), text, writtenAt, rewritten) } }
    }

    private fun CardKey.toStore(): SynthesisKey =
        SynthesisKey(
            kind = kind,
            entityKey = entityKey(entity),
            evidenceFingerprint = evidenceFingerprint,
            promptVersion = promptVersion,
            styleHash = styleHash,
            provider = provider,
            model = model,
            modelKnowledge = modelKnowledge,
            effort = effort,
        )

    // The entity key (architecture, *Cache and history*; #240): an artist or album by its MusicBrainz
    // id, else by its name, folded as the retrieval key folds names (an album's with the artist's
    // before it). Each part is length-prefixed, as the retrieval key's are, so no two blur. The kind
    // is part of the key too, so an artist's or album's never meets a track's.
    private fun entityKey(entity: CardEntity): String =
        when (entity) {
            // A card about more than the track (a genre's reason, #523) keeps its subject after it,
            // past a line break: the retrieval key's parts are length-prefixed, so the two never blur.
            is CardEntity.Track ->
                caches.retrieval.keyOf(entity.track, entity.duration) + entity.subject?.let { "\n$it" }.orEmpty()
            is CardEntity.Artist -> entity.mbid?.let { BY_ID + it } ?: byName(entity.name)
            is CardEntity.Album -> entity.group?.let { BY_ID + it } ?: byName(entity.artist, entity.title)
        }

    private fun byName(vararg names: String): String =
        BY_NAME + names.joinToString("") { name -> caches.retrieval.foldName(name).let { "${it.length}:$it" } }

    private companion object {
        const val BY_ID = "mb:"
        const val BY_NAME = "name:"
        const val LANGUAGE = "language:"
        const val DECISION = "decided-language"

        // The decision is kept as MusicBrainz's, for the shortest lifetime of the sources its hints
        // come from (MusicBrainz and Wikidata), at a version of its own.
        val DECIDED_BY = SourceId.MUSICBRAINZ
        val DECISION_CONSULTS = setOf(SourceId.MUSICBRAINZ, SourceId.WIKIDATA)
        const val DECISION_VERSION = 1
    }
}
