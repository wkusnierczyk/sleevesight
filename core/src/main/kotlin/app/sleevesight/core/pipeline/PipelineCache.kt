package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
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
 * @property subject what the card is about besides the track, null for none: for a genre's reason
 *   ([CardKind.GENRE], #523), the genre's identity (`GenreLabel.identity`), so each genre of a
 *   track keeps its own.
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
    val subject: String? = null,
)

/**
 * The hash of a card's user style (D14), SHA-256 in hex, as [CardKey.styleHash] keeps it. D14's style
 * is not built yet: every card uses the built-in style, the hash of no style ([NO_STYLE]).
 */
internal fun styleHash(
    hasher: Hasher,
    style: String = NO_STYLE,
): String =
    hasher.sha256(style.encodeToByteArray()).joinToString("") { byte ->
        val value = byte.toInt() and BYTE_MASK
        HEX[value shr NIBBLE].toString() + HEX[value and NIBBLE_MASK]
    }

/** No user style: the built-in style alone. */
internal const val NO_STYLE: String = ""

private const val HEX = "0123456789abcdef"
private const val BYTE_MASK = 0xFF
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0x0F
