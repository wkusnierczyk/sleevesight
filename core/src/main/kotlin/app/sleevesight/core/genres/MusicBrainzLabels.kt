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
 * in `:sources`, a fake in tests; [countedArtistTags] with their votes (#1019), which a fake that
 * gives only the names gives none of; it is what [MusicBrainzLabels] asks, so a wrapper that counts
 * or changes the requests overrides it, not [artistTags] alone. Kept apart from
 * [app.sleevesight.core.resolution.MusicBrainzLookup], which asks for core data only: tags are
 * CC BY-NC-SA 3.0, used for genre labels only and never sent in a prompt (`design/architecture.md`,
 * *MusicBrainz tags and genres (#9)*, *Conditions*).
 */
fun interface ArtistTags {
    suspend fun artistTags(mbid: String): Lookup<List<String>>

    /** As [artistTags], each tag with its net votes (#1019); by default with none known. */
    suspend fun countedArtistTags(mbid: String): Lookup<List<CountedTag>> = artistTags(mbid).uncounted()
}

/** The tags of [this] with no counts known, for a source or fake that gives the names only (#1019). */
internal fun Lookup<List<String>>.uncounted(): Lookup<List<CountedTag>> =
    when (this) {
        is Lookup.Found -> Lookup.Found(value.map { CountedTag(it) })
        Lookup.NotFound -> Lookup.NotFound
        is Lookup.Failed -> this
    }

/**
 * The genre labels from MusicBrainz's tags (D12, revised; #84): each credited artist's tags, in
 * credit order, through the [vocabulary], each with its weight by its votes (#1019) and linking its
 * artist's MusicBrainz page, uncapped: [combineLabels] caps each row once the sources' labels are
 * weighed together; and the recording's tags ([recordingLabels], #439), which
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
    private val kept = KeptTags(PART, CountedTag.serializer(), artists, clock, lifetime, capacity)

    /**
     * The labels from the tags of [artists], the credited artists MusicBrainz resolved, in credit
     * order, each label naming its artist; none for one whose request failed.
     */
    suspend fun labels(artists: List<ResolvedArtist>): List<GenreLabel> = lookUp(artists).labels

    /** As [labels], and whether any artist's request failed ([LabelLookup.failed], #772). */
    suspend fun lookUp(artists: List<ResolvedArtist>): LabelLookup {
        var failed = false
        val labels =
            artists
                .mapNotNull { artist -> artist.mbid?.let { artist.name to it } }
                .distinctBy { it.second }
                .flatMap { (name, mbid) ->
                    val found = kept.tagsOf(mbid) { tags.countedArtistTags(mbid) }
                    if (found == null) failed = true
                    vocabulary.sourceLabels(
                        found.orEmpty(),
                        artistPage(mbid),
                        GenreLevel.ARTIST,
                        name,
                        FULL_WEIGHT_VOTES,
                    )
                }
        return LabelLookup(labels, failed)
    }

    /**
     * The labels from the recording's tags ([RecordingTags]), each linking its MusicBrainz page and
     * carrying its tag's votes ([GenreLabel.votes], #799) and its weight by them (#1019), uncapped.
     */
    fun recordingLabels(recording: RecordingTags): List<GenreLabel> =
        vocabulary.labelsFor(
            recording.tags,
            recordingPage(recording.mbid),
            GenreLevel.TRACK,
            votes = recording.votes,
            counts = recording.votes,
            capped = false,
            fullCount = FULL_WEIGHT_VOTES,
        )

    companion object {
        /**
         * The votes from which a MusicBrainz tag weighs in full (1, `GenreLabel.weight`, #1019); a
         * tag with fewer weighs its share of them, its page's top tag too. A recording's tags are
         * often one contributor's one or two votes each (*rock, pop, alternative rock* on a folk
         * song, #799), where Last.fm's weights are over many listeners' tagging: weighed against
         * their own top tag alone, each would weigh as much as Last.fm's top tag and push out its
         * better backed ones (Siouxsie and the Banshees' "Spellbound", measured for #1019). Ten,
         * not five: a weak genre only MusicBrainz gives can never reach a prompt (D12), so it is of
         * little use, and makes room for the other sources' genres.
         */
        const val FULL_WEIGHT_VOTES: Int = 10

        /** As long as MusicBrainz's retrieval lifetime (D9): tags change no faster than its other data. */
        val DEFAULT_LIFETIME: Duration = 30.days

        /** A few hundred bytes an artist, so a few hundred artists cost the process little. */
        const val DEFAULT_CAPACITY: Int = 500

        /**
         * An artist's tags in the artist cache, by MusicBrainz id: a JSON array of the tags, most
         * voted first, each with its votes, as [ArtistTags.countedArtistTags] gave them; they go
         * through the vocabulary when read. Its extraction is 2: 1 kept the names alone, without
         * the votes their labels are weighed by (#1019), so an artist kept before is asked again.
         */
        val PART: ArtistPart = ArtistPart("musicbrainz.tags", 2, 1, setOf(SourceId.MUSICBRAINZ))

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
 * (`SourceResult.recordingTags`), kept with it in the retrieval cache. [votes] are each tag's net
 * votes, by tag (#799): a track's label is backed among the chips with at least two
 * ([TrackGenres.chips], #875); a tag missing from [votes] has no votes known (null), so it is backed
 * only when another level has its genre.
 */
data class RecordingTags(
    val mbid: String,
    val tags: List<String>,
    val votes: Map<String, Int> = emptyMap(),
)
