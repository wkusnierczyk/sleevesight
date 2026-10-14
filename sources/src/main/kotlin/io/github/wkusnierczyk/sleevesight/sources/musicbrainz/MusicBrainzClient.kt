package io.github.wkusnierczyk.sleevesight.sources.musicbrainz

import io.github.wkusnierczyk.sleevesight.core.resolution.Lookup
import io.github.wkusnierczyk.sleevesight.core.resolution.MbArtistRelation
import io.github.wkusnierczyk.sleevesight.core.resolution.MbCredit
import io.github.wkusnierczyk.sleevesight.core.resolution.MbPerformance
import io.github.wkusnierczyk.sleevesight.core.resolution.MbRecording
import io.github.wkusnierczyk.sleevesight.core.resolution.MbRecordingDetail
import io.github.wkusnierczyk.sleevesight.core.resolution.MbRelease
import io.github.wkusnierczyk.sleevesight.core.resolution.MbReleaseGroup
import io.github.wkusnierczyk.sleevesight.core.resolution.MbWork
import io.github.wkusnierczyk.sleevesight.core.resolution.MbWorkLink
import io.github.wkusnierczyk.sleevesight.core.resolution.MusicBrainzLookup
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.sources.array
import io.github.wkusnierczyk.sleevesight.sources.int
import io.github.wkusnierczyk.sleevesight.sources.long
import io.github.wkusnierczyk.sleevesight.sources.lookup
import io.github.wkusnierczyk.sleevesight.sources.obj
import io.github.wkusnierczyk.sleevesight.sources.parseObject
import io.github.wkusnierczyk.sleevesight.sources.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The MusicBrainz web service (`/ws/2`, JSON), keyless, through the one [NetClient], whose host
 * policy keeps it to one request a second and names the app in the User-Agent, as MusicBrainz
 * requires. Only core data (CC0) is asked for: never `inc=tags` or `inc=genres`, and the tags a
 * search returns anyway are not read (`design/architecture.md`, *MusicBrainz tags and genres*).
 *
 * Duration matching happens in `:core`, after the search, so the player's duration never leaves
 * the phone (R4).
 */
class MusicBrainzClient(
    private val net: NetClient,
) : MusicBrainzLookup {
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
        val request = NetRequest("$BASE/recording/$mbid", query = mapOf("inc" to RECORDING_INC, "fmt" to "json"))
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

    companion object {
        const val BASE: String = "https://musicbrainz.org/ws/2"

        /**
         * MusicBrainz's maximum. A much-recorded song has dozens of recordings by the same artist,
         * and the search ranks the original far down, differently from one request to the next:
         * with 50, Chuck Berry's 1956 "Roll Over Beethoven" was sometimes not among them (#78).
         */
        const val SEARCH_LIMIT: Int = 100

        const val RECORDING_INC: String = "artist-credits+work-rels+artist-rels+url-rels"

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
