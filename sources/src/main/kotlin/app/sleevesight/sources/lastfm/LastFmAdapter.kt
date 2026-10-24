package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import app.sleevesight.net.fetch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Last.fm (`design/design.md`, *Source adapters*, *Genre labels*): per credited artist, the
 * listeners, tags and similar artists, templated, and up to three bio sentences, verbatim and
 * attributed (D1; the bios are CC BY-SA). All lines are about the artist ([Scope.ARTIST]); Last.fm
 * is not asked about the recording. Genre labels come from the artist's top tags through
 * [vocabulary].
 *
 * Per D4 each credited artist is looked up by name at once (`artist.getInfo` and
 * `artist.getTopTags`, with `autocorrect=1`), and the [Resolution] is awaited only to judge the
 * answer ([ArtistCheck]): verified lines go in [SourceResult.evidence], unverified ones in
 * [SourceResult.unverified], and a rejected or unknown artist gives none. A page with the resolved
 * artist's MusicBrainz id is verified whole only if MusicBrainz ([musicBrainz]) shows the name is
 * that artist's alone; otherwise only its bio sentences are ([LastFmMatch.BioVerified]). That costs
 * one MusicBrainz search per such artist, after the resolution, unless an earlier track's answer
 * is kept. A failed search makes the result [SourceResult.provisional].
 *
 * Last.fm describes artists, so what it answers for a name is kept per artist in [artists], the
 * artist cache (#238, [ARTIST_PART]), by the name as sent, for Last.fm's lifetime (D9): a second
 * track by the same artist sends no `artist.getInfo` or `artist.getTopTags` while it is fresh, and
 * the verdicts of the search are kept there too ([ArtistCheck.OWNERSHIP_PART]). Each track's
 * answer is still judged against its own resolution, and its result says when the first part it
 * reused stops being fresh ([SourceResult.freshUntil]).
 *
 * The user's API key goes in [NetSecrets] as `api_key`, never in the URL.
 */
@Suppress("LongParameterList") // Eight independent dependencies; the clocks and the cache are defaulted, for tests.
class LastFmAdapter(
    private val net: NetClient,
    musicBrainz: MusicBrainzLookup,
    private val apiKey: Secret,
    private val vocabulary: GenreVocabulary,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val artists: ArtistResults = ArtistResults.None,
) : SourceAdapter {
    override val id: SourceId = SourceId.LASTFM

    override val extractionVersion: Int = EXTRACTION_VERSION

    /** The verification asks MusicBrainz for the artists of a name. */
    override val consults: Set<SourceId> = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)

    private val check = ArtistCheck(accentFolder, musicBrainz, artists)
    private val kept = KeptArtists(artists)

    override suspend fun lookup(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): SourceResult = lookupArtists(track, resolution).result

    /**
     * [lookup], with what the genre and similar-artist cards need besides the evidence: each
     * credited artist's answer, judgement and genre labels, in credit order.
     */
    suspend fun lookupArtists(
        track: NormalizedTrack,
        resolution: Deferred<Resolution>,
    ): LastFmLookup {
        val started = timeSource.markNow()
        val judged =
            coroutineScope {
                val fetches = track.artists.map { name -> name to async { fetchArtist(name) } }
                val resolved = resolution.await()
                fetches.map { (name, fetch) -> judge(name, fetch.await(), resolved) }
            }
        val answers = judged.map { it.lookup }
        val lines = judged.map { it.lookup.match to lines(it.lookup, it.retrievedAt) }
        val verified =
            lines.flatMap { (match, artist) ->
                when (match) {
                    LastFmMatch.Verified -> artist.bio + artist.aggregates
                    LastFmMatch.BioVerified -> artist.bio
                    else -> emptyList()
                }
            }
        val unverified =
            lines.flatMap { (match, artist) ->
                when (match) {
                    LastFmMatch.Unverified -> artist.bio + artist.aggregates
                    LastFmMatch.BioVerified -> artist.aggregates
                    else -> emptyList()
                }
            }
        val result =
            SourceResult(
                outcome = outcome(answers, hasLines = verified.isNotEmpty() || unverified.isNotEmpty()),
                evidence = verified,
                latency = started.elapsedNow(),
                unverified = unverified,
                rejections =
                    answers.mapNotNull { answer ->
                        (answer.match as? LastFmMatch.Rejected)?.let {
                            Rejection(
                                answer.credited,
                                it.reason,
                                Scope.ARTIST,
                            )
                        }
                    },
                genres =
                    answers
                        .flatMap { it.genres }
                        .distinctBy { it.identity }
                        .take(GenreVocabulary.MAX_LABELS),
                provisional = answers.any { it.provisional },
                freshUntil = judged.mapNotNull { it.freshUntil }.minOrNull(),
            )
        return LastFmLookup(result, answers)
    }

    // The answers for [name]: kept in the artist cache, or asked for and kept when they are final.
    private suspend fun fetchArtist(name: String): Fetched {
        kept.get(name)?.let { return it }
        val fetched =
            coroutineScope {
                val topTags = async { call("artist.gettoptags", name, LastFmJson::topTags) }
                val info = call("artist.getinfo", name, LastFmJson::artistInfo)
                Fetched(info, topTags.await(), clock.now())
            }
        kept.keep(name, fetched)
        return fetched
    }

    // Detekt does not see `NetClient.fetch`, from `:net`, as suspending; it is.
    @Suppress("RedundantSuspendModifier")
    private suspend fun <T> call(
        method: String,
        artist: String,
        parse: (String) -> LastFmAnswer<T>,
    ): LastFmAnswer<T> {
        val request =
            NetRequest(
                url = API_URL,
                query = mapOf("method" to method, "artist" to artist, "autocorrect" to "1", "format" to "json"),
                secrets = NetSecrets(query = mapOf("api_key" to apiKey.reveal())),
            )
        return when (val result = net.fetch(request) { response -> read(response, parse) }) {
            is NetResult.Success -> result.value
            is NetResult.Failure -> LastFmAnswer.Unreachable(result.error)
        }
    }

    /** A Last.fm error comes as JSON with any status; anything else outside 2xx is not an answer. */
    private fun <T> read(
        response: NetResponse,
        parse: (String) -> LastFmAnswer<T>,
    ): LastFmAnswer<T> {
        val answer = parse(response.body)
        check(response.isSuccess || answer is LastFmAnswer.Error) { "status ${response.status}" }
        return answer
    }

    /**
     * One credited artist judged: its [lookup], when its answers were retrieved, and, when it used
     * parts the artist cache kept, when the first of them stops being fresh.
     */
    private class Judged(
        val lookup: LastFmArtistLookup,
        fetched: Fetched,
        val freshUntil: Instant? = fetched.freshUntil,
    ) {
        val retrievedAt: Instant = fetched.retrievedAt
    }

    private suspend fun judge(
        credited: String,
        fetched: Fetched,
        resolution: Resolution,
    ): Judged =
        when (val info = fetched.info) {
            is LastFmAnswer.Found -> {
                val artist = info.value
                val judgement = check.judge(credited, artist, check.resolvedFor(credited, resolution.artists))
                val match = judgement.match
                val genres =
                    if (match.shows) {
                        vocabulary.labelsFor(
                            genreTags(artist, fetched.topTags),
                            artist.url,
                        )
                    } else {
                        emptyList()
                    }
                val freshUntil = listOfNotNull(fetched.freshUntil, judgement.freshUntil).minOrNull()
                Judged(LastFmArtistLookup(credited, match, artist, genres, judgement.provisional), fetched, freshUntil)
            }

            is LastFmAnswer.Error -> {
                val match =
                    if (info.code == LastFmJson.NOT_FOUND) {
                        LastFmMatch.NotFound
                    } else {
                        LastFmMatch.Failed("Last.fm error ${info.code}")
                    }
                Judged(LastFmArtistLookup(credited, match, null, emptyList()), fetched)
            }

            is LastFmAnswer.Unreachable -> {
                Judged(
                    LastFmArtistLookup(credited, LastFmMatch.Failed(info.error.toString()), null, emptyList()),
                    fetched,
                )
            }
        }

    /** The top tags above [MIN_TAG_COUNT], or the five tags of `artist.getInfo` when they did not arrive. */
    private fun genreTags(
        artist: LastFmArtist,
        topTags: LastFmAnswer<List<LastFmTag>>,
    ): List<String> {
        val tags = (topTags as? LastFmAnswer.Found)?.value
        return tags?.filter { it.count >= MIN_TAG_COUNT }?.map { it.name } ?: artist.tags
    }

    /** One artist's lines: the bio's sentences, and the aggregates every artist of the name adds to. */
    private class ArtistLines(
        val bio: List<EvidenceDraft>,
        val aggregates: List<EvidenceDraft>,
    )

    /** The lines for one artist, in a fixed order: bio sentences, listeners, tags, similar artists. */
    private fun lines(
        lookup: LastFmArtistLookup,
        retrievedAt: Instant,
    ): ArtistLines {
        val artist = lookup.shown ?: return ArtistLines(emptyList(), emptyList())
        val name = lookup.credited
        val bio =
            artist.bio.map { sentence ->
                EvidenceDraft(
                    id,
                    Scope.ARTIST,
                    "The Last.fm biography of $name says: \"$sentence\"",
                    artist.bioUrl ?: artist.url,
                    retrievedAt,
                )
            }
        val texts = mutableListOf<Pair<String, String?>>()
        artist.listeners?.takeIf { it > 0 }?.let {
            texts += "Last.fm counts ${grouped(it)} listeners for $name." to artist.url
        }
        if (artist.tags.isNotEmpty()) {
            texts += "Last.fm listeners tag $name as: ${artist.tags.joinToString(", ")}." to artist.url
        }
        if (artist.similar.isNotEmpty()) {
            val similar = artist.similar.take(MAX_SIMILAR).joinToString(", ") { it.name }
            texts += "Last.fm lists artists similar to $name by listening overlap: $similar." to artist.url
        }
        return ArtistLines(bio, texts.map { (text, url) -> EvidenceDraft(id, Scope.ARTIST, text, url, retrievedAt) })
    }

    companion object {
        const val API_URL: String = "https://ws.audioscrobbler.com/2.0/"

        /**
         * [extractionVersion]. 2: a page other MusicBrainz artists of its name may share has only its
         * bio verified (#75).
         */
        const val EXTRACTION_VERSION: Int = 2

        /**
         * What Last.fm answers for a credited artist's name (`artist.getInfo` and
         * `artist.getTopTags`), in the artist cache by the name as sent (#238): the artist as parsed
         * ([LastFmArtist], its bio as the sentences that may be quoted) with its top tags, or the
         * finding that Last.fm does not know the name. Judged afresh for each track.
         */
        val ARTIST_PART: ArtistPart = ArtistPart("lastfm.artist", EXTRACTION_VERSION, 1, setOf(SourceId.LASTFM))

        /**
         * Top tags weighted below this (of 100 for the artist's top tag) are a few listeners' and
         * do not become genre labels. A starting value, tuned in Phase 0.5 (#46).
         */
        const val MIN_TAG_COUNT: Int = 10

        /** `artist.getInfo` gives five similar artists. */
        const val MAX_SIMILAR: Int = 5

        private const val THOUSAND = 1000L
        private const val GROUP_DIGITS = 3

        /** [n] with a comma between thousands, as Last.fm's pages show it: `683,942`. */
        internal fun grouped(n: Long): String {
            val digits = n.toString()
            if (n < THOUSAND) return digits
            val head = digits.length % GROUP_DIGITS
            return buildString {
                digits.forEachIndexed { i, c ->
                    if (i > 0 && (i - head) % GROUP_DIGITS == 0) append(',')
                    append(c)
                }
            }
        }

        /**
         * The outcome of the whole lookup: any lines make it an artist article; else the first
         * rejection; else a failure; else none.
         */
        internal fun outcome(
            answers: List<LastFmArtistLookup>,
            hasLines: Boolean,
        ): Outcome {
            val rejection = answers.firstNotNullOfOrNull { it.match as? LastFmMatch.Rejected }
            return when {
                hasLines -> Outcome.ArtistArticle
                rejection != null -> Outcome.Rejected(rejection.reason)
                answers.any { it.match is LastFmMatch.Failed } -> Outcome.Unreachable
                else -> Outcome.None
            }
        }
    }
}

/**
 * A Last.fm lookup: [result] as [SourceAdapter.lookup] returns it, and each credited artist's
 * [artists], in credit order, for the genre and similar-artist cards.
 */
data class LastFmLookup(
    val result: SourceResult,
    val artists: List<LastFmArtistLookup>,
)
