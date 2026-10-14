package io.github.wkusnierczyk.sleevesight.core.pipeline

import io.github.wkusnierczyk.sleevesight.core.ai.ReasoningEffort
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.ProviderId
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.core.synthesis.CardKind
import kotlin.time.Duration

/**
 * The caches the pipeline consults first (`design/architecture.md`, *Cache and history*). An
 * interface here, implemented by `:store` (`StorePipelineCache`), because `:core` cannot see
 * `:store`: the store depends on the core, not the other way round. Calls suspend, so an
 * implementation over a blocking database moves them off the caller's thread.
 *
 * An implementation may throw; the pipeline then goes on as if it had missed, and logs it.
 */
interface PipelineCache {
    /**
     * What [adapter] gave for [track], played at [duration] (the player's length, or null), if kept
     * and current. The length is part of the key: resolution chooses the recording by it.
     */
    suspend fun result(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
    ): SourceResult?

    /**
     * Keeps what [adapter] gave for [track] at [duration]; the cache decides whether it may (not an
     * unreachable result, for one).
     */
    suspend fun keep(
        adapter: SourceAdapter,
        track: NormalizedTrack,
        duration: Duration?,
        result: SourceResult,
    )

    /** The card written for [key], if kept. */
    suspend fun card(key: CardKey): String?

    /** Keeps the card [text], written for [key]. */
    suspend fun keep(
        key: CardKey,
        text: String,
    )

    /** A cache that keeps nothing. */
    object None : PipelineCache {
        override suspend fun result(
            adapter: SourceAdapter,
            track: NormalizedTrack,
            duration: Duration?,
        ): SourceResult? = null

        override suspend fun keep(
            adapter: SourceAdapter,
            track: NormalizedTrack,
            duration: Duration?,
            result: SourceResult,
        ) = Unit

        override suspend fun card(key: CardKey): String? = null

        override suspend fun keep(
            key: CardKey,
            text: String,
        ) = Unit
    }
}

/**
 * What a written card depends on (R2, D14): the synthesis cache key, with the track itself as the
 * entity, which the store turns into its own key (the retrieval key, which tells apart tracks the
 * lookup key alone does not).
 *
 * @property evidenceFingerprint the fingerprint of the evidence the card was written from.
 * @property promptVersion the card's prompt template version.
 * @property styleHash a hash of the user's style the card used (D14), SHA-256 in hex.
 * @property duration the player's length for the track, part of its entity key.
 * @property modelKnowledge whether the model's own knowledge was allowed.
 * @property effort the reasoning effort asked for, null for the provider's default.
 */
data class CardKey(
    val kind: CardKind,
    val track: NormalizedTrack,
    val duration: Duration?,
    val evidenceFingerprint: String,
    val promptVersion: Int,
    val styleHash: String,
    val provider: ProviderId,
    val model: String,
    val modelKnowledge: Boolean,
    val effort: ReasoningEffort?,
)
