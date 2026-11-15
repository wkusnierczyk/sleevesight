package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import kotlin.time.Duration.Companion.seconds

// Fakes and builders shared by the resolver's tests.

private val names = NameMatcher(JvmAccentFolder)

private val normalizer = Normalizer(JvmAccentFolder)

internal fun track(
    title: String,
    artists: String,
): NormalizedTrack = normalizer.normalize(title, artists)

internal class FakeMusicBrainz(
    val search: Lookup<List<MbRecording>>,
    val recordings: Map<String, MbRecordingDetail> = emptyMap(),
    val works: Map<String, MbWork> = emptyMap(),
    /** A recording lookup that fails, with this reason, for every recording. */
    val recordingFailure: String? = null,
    /** A work lookup that fails, with this reason, for every work. */
    val workFailure: String? = null,
    /** Each recording with its releases (#724), by its id: a cover's original's. */
    val releases: Map<String, Lookup<MbRecording>> = emptyMap(),
) : MusicBrainzLookup {
    /** The search's answer by artist (#1129), [search] for an artist not here. */
    val searchesBy = mutableMapOf<String, Lookup<List<MbRecording>>>()

    /** The searches made, as (title, artist), in order. */
    val searched = mutableListOf<Pair<String, String>>()

    /** The searches by date made, as (title, artist), in order. */
    val beforeSearched = mutableListOf<Pair<String, String>>()

    /** The recordings whose releases were looked up, in order. */
    val releasesAsked = mutableListOf<String>()

    /** The search for the recordings released by a date (#906), whatever the date. */
    var before: Lookup<List<MbRecording>> = Lookup.NotFound

    /** Answers to the searches by date, in order, before [before]. */
    val beforeAnswers = mutableListOf<Lookup<List<MbRecording>>>()

    /** The dates searched by, in order. */
    val beforeAsked = mutableListOf<String>()

    override suspend fun searchRecordings(
        title: String,
        artist: String,
    ): Lookup<List<MbRecording>> {
        searched += title to artist
        return searchesBy[artist] ?: search
    }

    override suspend fun searchRecordingsBefore(
        title: String,
        artist: String,
        date: String,
    ): Lookup<List<MbRecording>> {
        beforeAsked += date
        beforeSearched += title to artist
        return beforeAnswers.removeFirstOrNull() ?: before
    }

    override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> {
        recordingsAsked += mbid
        if (mbid in failingRecordings || failingOnce.remove(mbid)) return Lookup.Failed("Timeout")
        return recordingFailure?.let { Lookup.Failed(it) }
            ?: recordings[mbid]?.let { Lookup.Found(it) }
            ?: Lookup.NotFound
    }

    override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> {
        releasesAsked += mbid
        return releases[mbid] ?: Lookup.NotFound
    }

    /** The works looked up, in order. */
    val worksAsked = mutableListOf<String>()

    /** The artists whose names were looked up (#1289), in order. */
    val artistNamesAsked = mutableListOf<String>()

    override suspend fun work(mbid: String): Lookup<MbWork> {
        worksAsked += mbid
        return workFailure?.let { Lookup.Failed(it) } ?: works[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
    }

    override suspend fun artistNames(mbid: String): Lookup<List<String>> {
        artistNamesAsked += mbid
        return Lookup.NotFound
    }

    /** The loose search's answer (#1289), whatever is asked. */
    var loose: Lookup<List<MbRecording>> = Lookup.NotFound

    /** The loose searches made, as (words, artists), in order. */
    val looseSearched = mutableListOf<Pair<List<String>, List<String>>>()

    /** The recordings looked up, in order. */
    val recordingsAsked = mutableListOf<String>()

    /** The recordings whose lookup fails (#1289), whatever [recordings] holds. */
    val failingRecordings = mutableSetOf<String>()

    /** The recordings whose first lookup fails and later ones answer (#1289). */
    val failingOnce = mutableSetOf<String>()

    override suspend fun searchRecordingsLoosely(
        words: List<String>,
        artists: List<String>,
    ): Lookup<List<MbRecording>> {
        looseSearched += words to artists
        return loose
    }

    /** The work search's answer (#1299), whatever is asked. */
    var foundWorks: Lookup<List<MbFoundWork>> = Lookup.NotFound

    /** The work searches made, as (title, composer), in order. */
    val worksSearched = mutableListOf<Pair<String, String>>()

    override suspend fun searchWorks(
        title: String,
        composer: String,
    ): Lookup<List<MbFoundWork>> {
        worksSearched += title to composer
        return foundWorks
    }

    // Resolution never searches for artists.
    override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = error("unexpected artist search")
}

internal fun credit(
    name: String,
    mbid: String = "mbid-$name",
) = MbCredit(name, mbid, name)

internal fun recording(
    mbid: String,
    title: String,
    artist: String,
    lengthSeconds: Int?,
    date: String?,
) = MbRecording(
    mbid = mbid,
    title = title,
    lengthMs = lengthSeconds?.let { it * 1000L },
    credits = listOf(credit(artist)),
    firstReleaseDate = date,
    releases =
        listOfNotNull(
            date?.let {
                MbRelease("release-$mbid", "Album $mbid", it, MbReleaseGroup("group-$mbid", "Album $mbid", "Album"))
            },
        ),
    score = 100,
)

internal suspend fun resolve(
    musicBrainz: MusicBrainzLookup,
    track: NormalizedTrack,
    seconds: Int? = 200,
): Resolution = RecordingResolver(musicBrainz, names).resolve(track, seconds?.seconds)
