package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import kotlin.time.Duration
import kotlin.time.Instant

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

    /**
     * The card written for [key], if kept, with when it was written, where the cache knows it (a
     * genre's reason, #523, is shown no longer than *Keep history for* after it), and what its rewrite
     * changed (#854); by default [card]'s, with no time and no rewrite.
     */
    suspend fun keptCard(key: CardKey): KeptCard? = card(key)?.let { KeptCard(it, null) }

    /** Keeps the card [text], written for [key]. */
    suspend fun keep(
        key: CardKey,
        text: String,
    )

    /**
     * Keeps the card [text], written for [key] at [writtenAt], the time the card shown carries too,
     * so the screen and the cache count *Keep history for* from the same time (#643); by default
     * as [keep] without one, where the cache keeps no time of its own.
     */
    suspend fun keep(
        key: CardKey,
        text: String,
        writtenAt: Instant,
    ) = keep(key, text)

    /**
     * Keeps the card [text], written for [key] at [writtenAt] by a rewrite that changed [rewritten]
     * (#854), so a later play says what it changed; by default as [keep] without them, where the
     * cache keeps none.
     */
    suspend fun keep(
        key: CardKey,
        text: String,
        writtenAt: Instant,
        rewritten: Set<RewritePart>,
    ) = keep(key, text, writtenAt)

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
 * A card kept: its [text], when it was written ([writtenAt]), null where the cache does not say, and
 * what the rewrite that wrote it changed ([rewritten], #854), none for one written the first time.
 */
data class KeptCard(
    val text: String,
    val writtenAt: Instant?,
    val rewritten: Set<RewritePart> = emptySet(),
)

/**
 * What a written card depends on (R2, D14): the synthesis cache key, with what the card is about,
 * its [entity], which the store turns into its own key (for a track, the retrieval key, which tells
 * apart tracks the lookup key alone does not; for an artist or album, its MusicBrainz id or its
 * folded name, #240).
 *
 * @property entity what the card is about: an [CardEntity.Artist] for [CardKind.ARTIST], an
 *   [CardEntity.Album] for [CardKind.ALBUM], and a [CardEntity.Track] for every other kind.
 * @property evidenceFingerprint the fingerprint of the evidence the card was written from: for an
 *   artist or album card, of the evidence gathered for the artist or album, not the track, so it is
 *   the same whichever of its tracks is playing.
 * @property promptVersion the card's prompt template version.
 * @property styleHash a hash of the user's style the card used (D14), SHA-256 in hex.
 * @property modelKnowledge whether the model's own knowledge was allowed.
 * @property effort the reasoning effort asked for, null for the provider's default.
 */
data class CardKey(
    val kind: CardKind,
    val entity: CardEntity,
    val evidenceFingerprint: String,
    val promptVersion: Int,
    val styleHash: String,
    val provider: ProviderId,
    val model: String,
    val modelKnowledge: Boolean,
    val effort: ReasoningEffort?,
) {
    init {
        val expected =
            when (kind) {
                CardKind.ARTIST -> entity is CardEntity.Artist
                CardKind.ALBUM -> entity is CardEntity.Album
                else -> entity is CardEntity.Track
            }
        require(expected) { "A ${kind.key} card is not about ${entity::class.simpleName}" }
    }

    /** What a track's card is about besides the track ([CardEntity.Track.subject]); null for any other. */
    val subject: String? get() = (entity as? CardEntity.Track)?.subject

    /** This track's card's key about [subject] instead ([CardEntity.Track.subject]). */
    fun about(subject: String?): CardKey {
        val track = entity as? CardEntity.Track
        requireNotNull(track) { "A ${kind.key} card has no subject" }
        return copy(entity = track.copy(subject = subject))
    }
}

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
