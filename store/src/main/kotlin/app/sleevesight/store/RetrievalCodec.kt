package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.SourceResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [SourceResult] as JSON, for the retrieval cache. Sources by their tag and scopes by their key,
 * the fixed names the evidence fingerprint also uses, so renaming a constant does not empty the
 * cache; instants and durations in ISO 8601, which round-trip exactly.
 */
internal object RetrievalCodec {
    /**
     * The encoding's version; a change to it makes every kept result a miss. 2: genre labels (#44),
     * which a result of version 1 would lack. 3: the key holds the player's length (#44), so entries
     * under the old key are dropped rather than left to expire. 4: a genre label's identity and the
     * page it links, by which labels from two sources combine and are credited, and the artists
     * whose MusicBrainz tags give labels (#84). The first release's date (#52) is an optional field
     * that older entries lack: MusicBrainz's extraction version, not this format, makes them misses.
     * 5: a rejection's scope (#420), without which a verdict on an artist's page would read as one
     * on the song's, and be kept and read while *Keep what sources said about each artist* is off.
     */
    const val FORMAT: Int = 5

    private val json = Json { encodeDefaults = false }

    private val SCOPES: Map<String, Scope> = Scope.entries.associateBy { it.key }

    /**
     * Text an encoded result holds only if it says something about an artist ([aboutArtists]): an
     * artist-level line or rejection (both with the artist scope), genre labels (from artists'
     * tags), or the artists' MusicBrainz ids. A string's own text cannot hold any of them, since its
     * quotation marks are escaped, and an empty list is not encoded. For deleting such results in
     * the database (`retrievalDeleteAboutArtists`, #420) without decoding every one.
     */
    val ARTIST_SCOPE_MARKER: String = "\"scope\":\"${Scope.ARTIST.key}\""
    const val GENRES_MARKER: String = "\"genres\":["
    const val LABEL_ARTISTS_MARKER: String = "\"labelArtists\":["

    /**
     * Whether [result] says something about an artist: an artist-level line ([Scope.ARTIST]), verified
     * or not; a verdict on an artist's page; genre labels, which come from artists' tags; or the
     * artists' MusicBrainz ids ([SourceResult.labelArtists]). Such a result is kept only while
     * *Keep what sources said about each artist* is on (#420), so while it is off MusicBrainz's
     * result, which names the artists it resolved, is not kept either.
     */
    fun aboutArtists(result: SourceResult): Boolean =
        (result.evidence + result.unverified).any { it.scope == Scope.ARTIST } ||
            result.rejections.any { it.scope == Scope.ARTIST } ||
            result.genres.isNotEmpty() ||
            result.labelArtists.isNotEmpty()

    fun encode(result: SourceResult): String =
        json.encodeToString(
            ResultJson.serializer(),
            ResultJson(
                outcome = outcomeKey(result.outcome),
                reason = (result.outcome as? Outcome.Rejected)?.reason,
                latency = result.latency.toIsoString(),
                evidence = result.evidence.map(::draftJson),
                unverified = result.unverified.map(::draftJson),
                rejections = result.rejections.map { RejectionJson(it.subject, it.reason, it.scope.key) },
                genres = result.genres.map { GenreJson(it.tag, it.family, it.hue, it.label, it.identity, it.url) },
                labelArtists = result.labelArtists,
                firstReleased = result.firstReleased,
            ),
        )

    /** The result in [payload], or null when it cannot be read (an unknown source or scope, say). */
    fun decode(payload: String): SourceResult? =
        try {
            val stored = json.decodeFromString(ResultJson.serializer(), payload)
            SourceResult(
                outcome = outcome(stored.outcome, stored.reason),
                evidence = stored.evidence.map(::draft),
                latency = Duration.parseIsoString(stored.latency),
                unverified = stored.unverified.map(::draft),
                rejections =
                    stored.rejections.map {
                        Rejection(
                            it.subject,
                            it.reason,
                            requireNotNull(SCOPES[it.scope]) { "Unknown scope ${it.scope}" },
                        )
                    },
                genres = stored.genres.map { GenreLabel(it.tag, it.family, it.hue, it.label, it.identity, it.url) },
                labelArtists = stored.labelArtists,
                firstReleased = stored.firstReleased,
            )
        } catch (_: IllegalArgumentException) {
            // `SerializationException` is one too.
            null
        }

    private fun outcomeKey(outcome: Outcome): String =
        when (outcome) {
            Outcome.SongArticle -> "song_article"
            Outcome.ArtistArticle -> "artist_article"
            Outcome.OriginalSong -> "original_song"
            is Outcome.Rejected -> "rejected"
            Outcome.None -> "none"
            Outcome.SourceOff -> "source_off"
            Outcome.Unreachable -> "unreachable"
        }

    private fun outcome(
        key: String,
        reason: String?,
    ): Outcome =
        when (key) {
            "song_article" -> Outcome.SongArticle
            "artist_article" -> Outcome.ArtistArticle
            "original_song" -> Outcome.OriginalSong
            "rejected" -> Outcome.Rejected(requireNotNull(reason) { "A rejection without a reason" })
            "none" -> Outcome.None
            "source_off" -> Outcome.SourceOff
            "unreachable" -> Outcome.Unreachable
            else -> throw IllegalArgumentException("Unknown outcome $key")
        }

    private fun draftJson(draft: EvidenceDraft): DraftJson =
        DraftJson(
            source = draft.source.tag,
            scope = draft.scope.key,
            text = draft.text,
            url = draft.url,
            retrievedAt = draft.retrievedAt.toString(),
        )

    private fun draft(stored: DraftJson): EvidenceDraft =
        EvidenceDraft(
            source = requireNotNull(SourceId.ofTag(stored.source)) { "Unknown source ${stored.source}" },
            scope = requireNotNull(SCOPES[stored.scope]) { "Unknown scope ${stored.scope}" },
            text = stored.text,
            url = stored.url,
            retrievedAt = Instant.parse(stored.retrievedAt),
        )

    @Serializable
    private data class ResultJson(
        val outcome: String,
        val reason: String? = null,
        val latency: String,
        val evidence: List<DraftJson> = emptyList(),
        val unverified: List<DraftJson> = emptyList(),
        val rejections: List<RejectionJson> = emptyList(),
        val genres: List<GenreJson> = emptyList(),
        val labelArtists: List<String> = emptyList(),
        val firstReleased: String? = null,
    )

    @Serializable
    private data class GenreJson(
        val tag: String,
        val family: String,
        val hue: Int,
        val label: String,
        val identity: String,
        val url: String? = null,
    )

    @Serializable
    private data class DraftJson(
        val source: String,
        val scope: String,
        val text: String,
        val url: String? = null,
        val retrievedAt: String,
    )

    @Serializable
    private data class RejectionJson(
        val subject: String,
        val reason: String,
        val scope: String = Scope.SONG.key,
    )
}
