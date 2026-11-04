package app.sleevesight.store

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import app.sleevesight.core.synthesis.CardKind
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Written insights and cards (architecture, *Cache and history*), kept until evicted by size, and
 * no longer than *Keep history for* ([lifetimeOf]): each is written about a track heard, so it goes
 * with the track's place in the history (#523 for a genre's reason, #643 for an insight). An
 * entry is reused only for exactly the same [SynthesisKey]: the same evidence (R2), the same
 * prompt template and style (D14), and the same provider, model and model-knowledge switch. Each
 * one is a paid call on the user's key (driver 5), so a hit saves money as well as time. One written
 * with another service, model or effort is never served as the key's, but is found by [others], for
 * a play to show it with *Rewrite insight* beside it (#911); a rewrite keeps both, each under its own
 * key.
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
        val now = context.clock.now()
        val writtenAt = Instant.fromEpochMilliseconds(row.written_at)
        val expired = pastKeepHistory(writtenAt, lifetimeOf(context.settings()), now)
        if (expired) {
            context.queries.synthesisDelete(row.id)
        } else {
            context.queries.synthesisTouch(now.toEpochMilliseconds(), row.id)
        }
        return if (expired) null else CachedSynthesis(row.text, writtenAt, RewritePart.decode(row.rewritten))
    }

    /**
     * What was written for [key]'s card with another AI service, model or effort (#911): each kept
     * card of the same kind, entity, evidence, prompt version, style and model-knowledge switch, under
     * its own key, the most recently written first; never [key]'s own. Those past *Keep history for*
     * are left out, as [get] leaves them, and so is one whose service or effort this app no longer
     * knows. Reading them marks none as used: only the one a play shows is, by [get] once it is
     * written again or chosen.
     */
    fun others(key: SynthesisKey): List<Pair<SynthesisKey, CachedSynthesis>> {
        val rows =
            context.queries
                .synthesisOthers(
                    kind = key.kind.key,
                    entityKey = key.entityKey,
                    evidenceFingerprint = key.evidenceFingerprint,
                    promptVersion = key.promptVersion.toLong(),
                    styleHash = key.styleHash,
                    modelKnowledge = key.modelKnowledge.toLong(),
                    provider = key.provider.name,
                    model = key.model,
                    effort = key.effortKey,
                ).executeAsList()
        val lifetime = lifetimeOf(context.settings())
        val now = context.clock.now()
        return rows.mapNotNull { row ->
            val writtenAt = Instant.fromEpochMilliseconds(row.written_at)
            val provider = ProviderId.entries.firstOrNull { it.name == row.provider }
            val effort = effortOf(row.effort)
            if (pastKeepHistory(writtenAt, lifetime, now) || provider == null || effort == null) {
                null
            } else {
                val other = key.copy(provider = provider, model = row.model, effort = effort.value)
                other to CachedSynthesis(row.text, writtenAt, RewritePart.decode(row.rewritten))
            }
        }
    }

    // A stored effort ([SynthesisKey.effortKey]) as the key holds it, wrapped so the default (null)
    // is told apart from an effort this app no longer knows (null itself).
    private fun effortOf(stored: String): Effort? =
        if (stored == SynthesisKey.DEFAULT_EFFORT) {
            Effort(null)
        } else {
            ReasoningEffort.entries.firstOrNull { it.name.lowercase() == stored }?.let(::Effort)
        }

    private class Effort(
        val value: ReasoningEffort?,
    )

    /**
     * Keeps [text], written for [key] at [writtenAt] by a rewrite that changed [rewritten] (#854,
     * none for one written the first time), replacing what was kept, and evicts down to the size
     * limit. Returns whether it was kept: not when it alone is larger than the limit.
     */
    fun put(
        key: SynthesisKey,
        text: String,
        writtenAt: Instant = context.clock.now(),
        rewritten: Set<RewritePart> = emptySet(),
    ): Boolean {
        val parts = RewritePart.encode(rewritten)
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
        val size = keyBytes + text.utf8Size() + (parts?.utf8Size() ?: 0)
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
                rewritten = parts,
            )
            context.afterWrite()
        }
        return true
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    internal companion object {
        /**
         * How long a card of any kind is kept under [settings], besides the size limit: for *Keep
         * history for* ([Settings.historyLifetime], null for *Forever*), counted from when it was
         * written. A card is written about a track heard (the insight, #643; a genre's reason,
         * #523), so it is kept with the track, like its place in the history. Read on every call,
         * so a shorter setting stops serving older cards at once, and [Caches.purgeExpired]
         * deletes them at its next run.
         */
        fun lifetimeOf(settings: Settings): Duration? = settings.historyLifetime
    }
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
    internal val effortKey: String get() = effort?.name?.lowercase() ?: DEFAULT_EFFORT

    internal companion object {
        /** [effortKey] for the provider's default effort. */
        const val DEFAULT_EFFORT = "default"
    }
}

/** A kept card: its [text], when it was written, and what the rewrite that wrote it changed (#854). */
data class CachedSynthesis(
    val text: String,
    val writtenAt: Instant,
    val rewritten: Set<RewritePart> = emptySet(),
)
