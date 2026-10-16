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
) : MusicBrainzLookup {
    override suspend fun searchRecordings(
        title: String,
        artist: String,
    ) = search

    override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> =
        recordings[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound

    override suspend fun work(mbid: String): Lookup<MbWork> = works[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound

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
