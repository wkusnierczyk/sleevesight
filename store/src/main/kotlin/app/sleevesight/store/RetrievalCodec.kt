package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
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
     * 5: a rejection's scope (#420). 6: whether the result is about the track alone
     * ([SourceResult.trackOnly], #420), without which an entry cannot say whether it may be kept
     * while *Keep what sources said about each artist* is off. 7: each artist's genre label names its
     * artist, the artists whose MusicBrainz tags give labels are kept by name too, and MusicBrainz's
     * result holds the recording's tags (#439); a label no longer keeps a hue. The dates of the
     * releases' titles (#556) are an optional field like the first release's, made a miss the same way.
     * The first release with its title and type, and a cover's original with its release (#724), are
     * optional fields too, which an entry kept before them decodes without: no miss, the home screen's
     * *Dates* says what the first release's date alone gives.
     */
    const val FORMAT: Int = 7

    private val json = Json { encodeDefaults = false }

    private val SCOPES: Map<String, Scope> = Scope.entries.associateBy { it.key }

    /**
     * Text an encoded result holds only if it is about the track alone ([SourceResult.trackOnly]):
     * the field is not encoded when false, and a string's own text cannot hold it, since its
     * quotation marks are escaped. For deleting every other entry in the database
     * (`retrievalDeleteAboutArtists`, #420) without decoding each.
     */
    const val TRACK_ONLY_MARKER: String = "\"trackOnly\":true"

    /**
     * Whether [result] may say something about an artist: unless its adapter marked it as about
     * the track alone ([SourceResult.trackOnly]), it is taken to (#420). Such a result is kept only
     * while *Keep what sources said about each artist* is on.
     */
    fun aboutArtists(result: SourceResult): Boolean = !result.trackOnly

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
                genres = result.genres.map { GenreJson(it.tag, it.family, it.label, it.identity, it.url, it.artist) },
                labelArtists = result.labelArtists.map { LabelArtistJson(it.name, it.mbid) },
                recordingTags = result.recordingTags?.let { RecordingTagsJson(it.mbid, it.tags) },
                firstReleased = result.firstReleased,
                releaseDates = result.releaseDates.map { ReleaseDateJson(it.title, it.date, it.group) },
                firstRelease = result.firstRelease?.let(::releaseJson),
                original =
                    result.original?.let {
                        OriginalJson(
                            recordingMbid = it.recordingMbid,
                            title = it.title,
                            artists = it.artists.map { artist -> LabelArtistJson(artist.name, artist.mbid) },
                            artistCredit = it.artistCredit,
                            date = it.date,
                            release = it.release?.let(::releaseJson),
                        )
                    },
                trackOnly = result.trackOnly,
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
                // Kept results hold the artists' labels only: the track's and album's are asked apart.
                genres =
                    stored.genres.map {
                        GenreLabel(it.tag, it.family, it.label, it.identity, it.url, GenreLevel.ARTIST, it.artist)
                    },
                labelArtists = stored.labelArtists.map { ResolvedArtist(it.name, it.mbid) },
                recordingTags = stored.recordingTags?.let { RecordingTags(it.mbid, it.tags) },
                firstReleased = stored.firstReleased,
                releaseDates = stored.releaseDates.map { ReleaseDate(it.title, it.date, it.group) },
                firstRelease = stored.firstRelease?.let(::release),
                original =
                    stored.original?.let {
                        OriginalRecording(
                            recordingMbid = it.recordingMbid,
                            title = it.title,
                            artists = it.artists.map { artist -> ResolvedArtist(artist.name, artist.mbid) },
                            artistCredit = it.artistCredit,
                            date = it.date,
                            release = it.release?.let(::release),
                        )
                    },
                trackOnly = stored.trackOnly,
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

    private fun releaseJson(release: FirstRelease): FirstReleaseJson =
        FirstReleaseJson(release.date, release.title, release.releaseGroupMbid, release.releaseMbid, release.type)

    private fun release(stored: FirstReleaseJson): FirstRelease =
        FirstRelease(stored.date, stored.title, stored.releaseGroupMbid, stored.releaseMbid, stored.type)

    private fun draftJson(draft: EvidenceDraft): DraftJson =
        DraftJson(
            source = draft.source.tag,
            scope = draft.scope.key,
            text = draft.text,
            url = draft.url,
            retrievedAt = draft.retrievedAt.toString(),
            aggregate = draft.aggregate,
        )

    private fun draft(stored: DraftJson): EvidenceDraft =
        EvidenceDraft(
            source = requireNotNull(SourceId.ofTag(stored.source)) { "Unknown source ${stored.source}" },
            scope = requireNotNull(SCOPES[stored.scope]) { "Unknown scope ${stored.scope}" },
            text = stored.text,
            url = stored.url,
            retrievedAt = Instant.parse(stored.retrievedAt),
            aggregate = stored.aggregate,
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
        val labelArtists: List<LabelArtistJson> = emptyList(),
        val recordingTags: RecordingTagsJson? = null,
        val firstReleased: String? = null,
        val releaseDates: List<ReleaseDateJson> = emptyList(),
        val firstRelease: FirstReleaseJson? = null,
        val original: OriginalJson? = null,
        val trackOnly: Boolean = false,
    )

    // A first release (#724): of the recording, or of a cover's original.
    @Serializable
    private data class FirstReleaseJson(
        val date: String,
        val title: String,
        val releaseGroupMbid: String? = null,
        val releaseMbid: String,
        val type: String? = null,
    )

    @Serializable
    private data class OriginalJson(
        val recordingMbid: String,
        val title: String,
        val artists: List<LabelArtistJson> = emptyList(),
        val artistCredit: String,
        val date: String,
        val release: FirstReleaseJson? = null,
    )

    @Serializable
    private data class ReleaseDateJson(
        val title: String,
        val date: String? = null,
        val group: String,
    )

    @Serializable
    private data class GenreJson(
        val tag: String,
        val family: String,
        val label: String,
        val identity: String,
        val url: String? = null,
        val artist: String? = null,
    )

    @Serializable
    private data class LabelArtistJson(
        val name: String,
        val mbid: String?,
    )

    @Serializable
    private data class RecordingTagsJson(
        val mbid: String,
        val tags: List<String>,
    )

    @Serializable
    private data class DraftJson(
        val source: String,
        val scope: String,
        val text: String,
        val url: String? = null,
        val retrievedAt: String,
        // A line of counts and lists (#617, `EvidenceDraft.aggregate`); left out when false.
        val aggregate: Boolean = false,
    )

    @Serializable
    private data class RejectionJson(
        val subject: String,
        val reason: String,
        val scope: String = Scope.SONG.key,
    )
}
