package app.sleevesight.store

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.synthesis.CardKind
import kotlin.time.Instant

/**
 * Written insights and cards (architecture, *Cache and history*), kept until evicted by size. An
 * entry is reused only for exactly the same [SynthesisKey]: the same evidence (R2), the same
 * prompt template and style (D14), and the same provider, model and model-knowledge switch. Each
 * one is a paid call on the user's key (driver 5), so a hit saves money as well as time.
 */
class SynthesisCache internal constructor(
    private val context: CacheContext,
) {
    /** What was written for [key], if kept. */
    fun get(key: SynthesisKey): CachedSynthesis? {
        val row =
            context.queries
                .synthesisGet(
                    kind = key.kind.key,
                    entityKey = key.entityKey,
                    evidenceFingerprint = key.evidenceFingerprint,
                    promptVersion = key.promptVersion.toLong(),
                    styleHash = key.styleHash,
                    provider = key.provider.name,
                    model = key.model,
                    modelKnowledge = key.modelKnowledge.toLong(),
                    effort = key.effortKey,
                ).executeAsOneOrNull() ?: return null
        context.queries.synthesisTouch(context.clock.now().toEpochMilliseconds(), row.id)
        return CachedSynthesis(row.text, Instant.fromEpochMilliseconds(row.written_at))
    }

    /**
     * Keeps [text], written for [key] at [writtenAt], replacing what was kept, and evicts down to
     * the size limit. Returns whether it was kept: not when it alone is larger than the limit.
     */
    fun put(
        key: SynthesisKey,
        text: String,
        writtenAt: Instant = context.clock.now(),
    ): Boolean {
        val keyBytes =
            listOf(
                key.kind.key,
                key.entityKey,
                key.evidenceFingerprint,
                key.styleHash,
                key.provider.name,
                key.model,
                key.effortKey,
            ).sumOf { it.utf8Size() }
        val size = keyBytes + text.utf8Size()
        if (!context.fits(size)) return false
        val now = context.clock.now().toEpochMilliseconds()
        context.queries.transaction {
            context.queries.synthesisRemoveKey(
                kind = key.kind.key,
                entityKey = key.entityKey,
                evidenceFingerprint = key.evidenceFingerprint,
                promptVersion = key.promptVersion.toLong(),
                styleHash = key.styleHash,
                provider = key.provider.name,
                model = key.model,
                modelKnowledge = key.modelKnowledge.toLong(),
                effort = key.effortKey,
            )
            context.queries.synthesisInsert(
                kind = key.kind.key,
                entity_key = key.entityKey,
                evidence_fingerprint = key.evidenceFingerprint,
                prompt_version = key.promptVersion.toLong(),
                style_hash = key.styleHash,
                provider = key.provider.name,
                model = key.model,
                model_knowledge = key.modelKnowledge.toLong(),
                effort = key.effortKey,
                text = text,
                written_at = writtenAt.toEpochMilliseconds(),
                used_at = now,
                size_bytes = size,
            )
            context.afterWrite()
        }
        return true
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}

/**
 * What a written card depends on (architecture, *Cache and history*; R2, D14).
 *
 * @property kind the card: the home insight, a song, artist or album insight, the genre or
 *   sounds-like card, stored by its fixed [CardKind.key].
 * @property entityKey what the card is about: for a track, its [RetrievalCache.keyOf], which tells
 *   apart tracks the lookup key alone does not; for an artist or album, its own key.
 * @property evidenceFingerprint [app.sleevesight.core.evidence.EvidenceSet.fingerprint]
 *   of the evidence it was written from (R2).
 * @property promptVersion the version of the card's prompt template, raised when its contract or
 *   built-in style changes.
 * @property styleHash a hash of the user's style the card used (D14).
 * @property modelKnowledge whether the model's own knowledge was allowed.
 * @property effort the reasoning effort the card was written at, null for the provider's default:
 *   a raised effort writes afresh rather than serving a card written at the old one.
 */
data class SynthesisKey(
    val kind: CardKind,
    val entityKey: String,
    val evidenceFingerprint: String,
    val promptVersion: Int,
    val styleHash: String,
    val provider: ProviderId,
    val model: String,
    val modelKnowledge: Boolean,
    val effort: ReasoningEffort?,
) {
    /** [effort] as stored: its name, or `default`. */
    internal val effortKey: String get() = effort?.name?.lowercase() ?: "default"
}

/** A kept card: its [text], and when it was written. */
data class CachedSynthesis(
    val text: String,
    val writtenAt: Instant,
)
