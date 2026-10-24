package app.sleevesight.core.genres

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.ResolvedArtist
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * An artist's MusicBrainz tags (`inc=tags` on the artist), most voted first: `MusicBrainzClient`
 * in `:sources`, a fake in tests. Kept apart from [app.sleevesight.core.resolution.MusicBrainzLookup],
 * which asks for core data only: tags are CC BY-NC-SA 3.0, used for genre labels only and never
 * sent in a prompt (`design/architecture.md`, *MusicBrainz tags and genres (#9)*, *Conditions*).
 */
fun interface ArtistTags {
    suspend fun artistTags(mbid: String): Lookup<List<String>>
}

/**
 * The genre labels from MusicBrainz's tags (D12, revised; #84): each credited artist's tags, in
 * credit order, through the [vocabulary], at most [GenreVocabulary.MAX_LABELS] an artist, each
 * linking its artist's MusicBrainz page; and the recording's tags ([recordingLabels], #439), which
 * the resolution's recording lookup brings with no request of its own. The pipeline asks for the
 * artists' after the evidence is assembled, for the artists MusicBrainz resolved
 * (`SourceResult.labelArtists`), so the insight never waits for them. MusicBrainz's release tags
 * would take a request of their own, so it gives no album labels.
 *
 * Tags are artist-level, so an artist's are kept by MusicBrainz id ([KeptTags], under [PART]): a
 * second track by the same artist asks nothing. A failed request gives that artist no labels
 * meanwhile; nothing else follows from it, since the labels are no evidence.
 */
class MusicBrainzLabels(
    private val tags: ArtistTags,
    private val vocabulary: GenreVocabulary,
    clock: Clock = Clock.System,
    lifetime: Duration = DEFAULT_LIFETIME,
    capacity: Int = DEFAULT_CAPACITY,
    artists: ArtistResults = ArtistResults.None,
) {
    private val kept = KeptTags(PART, artists, clock, lifetime, capacity)

    /**
     * The labels from the tags of [artists], the credited artists MusicBrainz resolved, in credit
     * order, each label naming its artist; none for one whose request failed.
     */
    suspend fun labels(artists: List<ResolvedArtist>): List<GenreLabel> =
        artists
            .mapNotNull { artist -> artist.mbid?.let { artist.name to it } }
            .distinctBy { it.second }
            .flatMap { (name, mbid) ->
                val found = kept.tagsOf(mbid) { tags.artistTags(mbid) }.orEmpty()
                vocabulary.labelsFor(found, artistPage(mbid), GenreLevel.ARTIST, name)
            }

    /** The labels from the recording's tags ([RecordingTags]), each linking its MusicBrainz page. */
    fun recordingLabels(recording: RecordingTags): List<GenreLabel> =
        vocabulary.labelsFor(recording.tags, recordingPage(recording.mbid), GenreLevel.TRACK)

    companion object {
        /** As long as MusicBrainz's retrieval lifetime (D9): tags change no faster than its other data. */
        val DEFAULT_LIFETIME: Duration = 30.days

        /** A few hundred bytes an artist, so a few hundred artists cost the process little. */
        const val DEFAULT_CAPACITY: Int = 500

        /**
         * An artist's tags in the artist cache, by MusicBrainz id: a JSON array of the tags, most
         * voted first, as [ArtistTags] gave them. Its extraction is 1: the tags are kept as given,
         * and go through the vocabulary when read.
         */
        val PART: ArtistPart = ArtistPart("musicbrainz.tags", 1, 1, setOf(SourceId.MUSICBRAINZ))

        /** The artist's page on MusicBrainz, which a label from its tags links. */
        fun artistPage(mbid: String): String = "https://musicbrainz.org/artist/$mbid"

        /** The recording's page on MusicBrainz, which a label from its tags links. */
        fun recordingPage(mbid: String): String = "https://musicbrainz.org/recording/$mbid"
    }
}

/**
 * The accepted recording's MusicBrainz tags (#439), most voted first, those with no net vote up
 * left out: CC BY-NC-SA 3.0, for genre labels only and never in a prompt. The resolution's
 * recording lookup brings them (`inc=tags`), and MusicBrainz's result carries them
 * (`SourceResult.recordingTags`), kept with it in the retrieval cache.
 */
data class RecordingTags(
    val mbid: String,
    val tags: List<String>,
)
