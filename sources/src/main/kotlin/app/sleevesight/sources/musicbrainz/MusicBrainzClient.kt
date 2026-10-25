package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.ArtistTags
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtist
import app.sleevesight.core.resolution.MbArtistRelation
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MbCredit
import app.sleevesight.core.resolution.MbPerformance
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbRelease
import app.sleevesight.core.resolution.MbReleaseGroup
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.resolution.MbWorkLink
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.sources.array
import app.sleevesight.sources.int
import app.sleevesight.sources.long
import app.sleevesight.sources.lookup
import app.sleevesight.sources.obj
import app.sleevesight.sources.parseObject
import app.sleevesight.sources.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The MusicBrainz web service (`/ws/2`, JSON), keyless, through the one [NetClient], whose host
 * policy keeps it to one request a second and names the app in the User-Agent, as MusicBrainz
 * requires. Resolution asks for core data (CC0), never `inc=genres`, and the tags a search returns
 * anyway are not read. Tags are asked for in two places, behind the MusicBrainz genre labels (D12,
 * #84): [artistTags] (`inc=tags` on an artist), and the recording's lookup ([recording], `inc=tags`
 * among its others, #439), which gives the track's with no request of its own. Tags are CC BY-NC-SA
 * 3.0, used for labels only and never sent in a prompt (`design/architecture.md`, *MusicBrainz tags
 * and genres (#9)*).
 *
 * Duration matching happens in `:core`, after the search, so the player's duration never leaves
 * the phone (R4).
 *
 * [tags] says, at each recording's lookup, whether its tags are asked for: not while *Show genres*
 * is off (#444), when no genre information is asked for from any source.
 */
class MusicBrainzClient(
    private val net: NetClient,
    private val tags: () -> Boolean = { true },
) : MusicBrainzLookup,
    ArtistTags {
    override suspend fun searchRecordings(
        title: String,
        artist: String,
    ): Lookup<List<MbRecording>> {
        val query = "recording:${phrase(title)} AND artist:${phrase(artist)}"
        val request =
            NetRequest(
                "$BASE/recording",
                query =
                    mapOf(
                        "query" to query,
                        "limit" to "$SEARCH_LIMIT",
                        "fmt" to "json",
                    ),
            )
        return net.lookup(request) { response ->
            parseObject(response.body).array("recordings").map { recording(it) }
        }
    }

    override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> {
        val inc = if (tags()) RECORDING_INC else RECORDING_INC_WITHOUT_TAGS
        val request = NetRequest("$BASE/recording/$mbid", query = mapOf("inc" to inc, "fmt" to "json"))
        return net.lookup(request) { response ->
            val json = parseObject(response.body)
            val relations = json.array("relations")
            MbRecordingDetail(
                mbid = json.required("id"),
                title = json.required("title"),
                credits = credits(json),
                firstReleaseDate = json.string("first-release-date")?.ifEmpty { null },
                works =
                    relations
                        .filter { it.string("target-type") == "work" && it.string("type") == "performance" }
                        .mapNotNull { relation ->
                            relation.obj("work")?.let { work ->
                                MbWorkLink(work.required("id"), work.required("title"), "cover" in attributes(relation))
                            }
                        },
                artistRelations = artistRelations(relations),
                wikidataIds = wikidataIds(relations),
                tags = tags(json),
            )
        }
    }

    override suspend fun work(mbid: String): Lookup<MbWork> {
        val request = NetRequest("$BASE/work/$mbid", query = mapOf("inc" to WORK_INC, "fmt" to "json"))
        return net.lookup(request) { response ->
            val json = parseObject(response.body)
            val relations = json.array("relations")
            MbWork(
                mbid = json.required("id"),
                title = json.required("title"),
                artistRelations = artistRelations(relations),
                wikidataIds = wikidataIds(relations),
                performances =
                    relations
                        .filter { it.string("target-type") == "recording" && it.string("type") == "performance" }
                        .mapNotNull { relation ->
                            relation.obj("recording")?.let { recording ->
                                MbPerformance(
                                    recordingMbid = recording.required("id"),
                                    title = recording.required("title"),
                                    credits = credits(recording),
                                    date = relation.string("begin")?.ifEmpty { null },
                                    attributes = attributes(relation),
                                )
                            }
                        },
            )
        }
    }

    override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> {
        val query = mapOf("query" to "artist:${phrase(name)}", "limit" to "$ARTIST_SEARCH_LIMIT", "fmt" to "json")
        return net.lookup(NetRequest("$BASE/artist", query = query)) { response ->
            val json = parseObject(response.body)
            // Both are required: an answer without them cannot show what matches. A count below the
            // artists listed is as good as none.
            val artists = json["artists"] as? JsonArray ?: error("no artists")
            val count = json.int("count") ?: error("no count")
            check(count >= artists.size) { "a count below the artists listed" }
            MbArtistSearch(
                total = count,
                artists = artists.map { MbArtist(it.required("id"), it.required("name")) },
            )
        }
    }

    /**
     * The artist's tags, most voted first (ties by name, so the order is the same for the same
     * answer), those with no net vote up left out ([tags]). Only the tags are read, not the artist.
     */
    override suspend fun artistTags(mbid: String): Lookup<List<String>> {
        val request = NetRequest("$BASE/artist/$mbid", query = mapOf("inc" to "tags", "fmt" to "json"))
        return net.lookup(request) { response ->
            val json = parseObject(response.body)
            json.required("id")
            tags(json)
        }
    }

    companion object {
        const val BASE: String = "https://musicbrainz.org/ws/2"

        /**
         * Artists in the one page asked for, MusicBrainz's maximum. The search does not rank the
         * artists of exactly the name asked for first: of the 2,150 artists matching "Fred", the ten
         * named Fred ranked from 25th to 97th (2026-10-15).
         */
        const val ARTIST_SEARCH_LIMIT: Int = 100

        /**
         * MusicBrainz's maximum. A much-recorded song has dozens of recordings by the same artist,
         * and the search ranks the original far down, differently from one request to the next:
         * with 50, Chuck Berry's 1956 "Roll Over Beethoven" was sometimes not among them (#78).
         */
        const val SEARCH_LIMIT: Int = 100

        /** With `tags`, for the track's genre labels (#439), which only [MbRecordingDetail.tags] holds. */
        const val RECORDING_INC: String = "artist-credits+work-rels+artist-rels+url-rels+tags"

        /** [RECORDING_INC] without `tags`, while no genre information is asked for (#444). */
        const val RECORDING_INC_WITHOUT_TAGS: String = "artist-credits+work-rels+artist-rels+url-rels"

        const val WORK_INC: String = "artist-rels+url-rels+recording-rels+artist-credits"

        private val WIKIDATA = Regex("^https?://www\\.wikidata\\.org/wiki/(Q[0-9]+)$")

        // Lucene: a quoted phrase, with the two characters that are special inside quotes escaped.
        internal fun phrase(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun recording(json: JsonElement): MbRecording =
            MbRecording(
                mbid = json.required("id"),
                title = json.required("title"),
                lengthMs = json.long("length"),
                credits = credits(json),
                firstReleaseDate = json.string("first-release-date")?.ifEmpty { null },
                releases =
                    json.array("releases").map { release ->
                        MbRelease(
                            mbid = release.required("id"),
                            title = release.required("title"),
                            date = release.string("date")?.ifEmpty { null },
                            releaseGroup =
                                release.obj("release-group")?.let {
                                    MbReleaseGroup(it.required("id"), it.required("title"), it.string("primary-type"))
                                },
                        )
                    },
                score = json.int("score") ?: 0,
            )

        // An entity's tags, most voted first, ties by name, those with no net vote up left out.
        private fun tags(json: JsonElement): List<String> =
            json
                .array("tags")
                .mapNotNull { tag -> tag.string("name")?.let { name -> name to (tag.int("count") ?: 0) } }
                .filter { (_, count) -> count > 0 }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }

        private fun credits(json: JsonElement): List<MbCredit> =
            json.array("artist-credit").mapNotNull { credit ->
                credit.obj("artist")?.let { artist ->
                    val artistName = artist.required("name")
                    MbCredit(
                        name = credit.string("name") ?: artistName,
                        artistMbid = artist.required("id"),
                        artistName = artistName,
                        joinPhrase = credit.string("joinphrase").orEmpty(),
                    )
                }
            }

        private fun artistRelations(relations: List<JsonElement>): List<MbArtistRelation> =
            relations
                .filter { it.string("target-type") == "artist" }
                .mapNotNull { relation ->
                    relation.obj("artist")?.let { artist ->
                        MbArtistRelation(
                            type = relation.required("type"),
                            artistMbid = artist.required("id"),
                            artistName = artist.required("name"),
                            attributes = attributes(relation),
                        )
                    }
                }

        private fun attributes(relation: JsonElement): List<String> =
            relation.array("attributes").mapNotNull { (it as? JsonPrimitive)?.content }

        private fun wikidataIds(relations: List<JsonElement>): List<String> =
            relations
                .filter { it.string("target-type") == "url" && it.string("type") == "wikidata" }
                .mapNotNull { relation ->
                    relation.obj("url")?.string("resource")?.let { WIKIDATA.find(it)?.groupValues?.get(1) }
                }.distinct()

        private fun JsonElement.required(key: String): String = string(key) ?: throw MissingField(key)
    }
}

/** A field MusicBrainz always sends is missing: the answer is not what the client expects. */
internal class MissingField(
    field: String,
) : IllegalStateException("missing field $field")
