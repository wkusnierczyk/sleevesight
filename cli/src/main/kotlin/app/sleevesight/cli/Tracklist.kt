package app.sleevesight.cli

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tracks to run in place of the fixture playlist (`--tracklist`), in the order given, as a player
 * would report them one after another: an album played through, or a playlist of one artist, for
 * measuring what consecutive tracks send and write (#239, [ArtistFacts]). A run over them is
 * numbered from 1 in the file's order; the gate's collisions, which are fixture playlist
 * positions, do not apply to it.
 *
 * The file is JSON, in the fixture playlist's field names, with the release added:
 *
 * ```json
 * {"name": "Rubber Soul", "tracks": [
 *   {"title": "Drive My Car", "artist": "The Beatles", "album": "Rubber Soul", "dur": "2:30"}
 * ]}
 * ```
 *
 * `title` and `artist` (the player's artist string, names separated by commas) are required;
 * `album` and `dur` (`m:ss`) are optional, as a player may not report them; `name` is a label.
 */
class Tracklist(
    val name: String?,
    val tracks: List<NowPlaying>,
) {
    companion object {
        /** Reads [file]; throws [IllegalArgumentException] with the reason for a file it cannot use. */
        fun read(file: File): Tracklist {
            require(file.isFile) { "No tracklist at ${file.path}" }
            return parse(file.readText())
        }

        /** Parses a tracklist's [text]; throws [IllegalArgumentException] with the reason for bad input. */
        fun parse(text: String): Tracklist {
            val root =
                try {
                    Json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("The tracklist is not JSON: ${e.message}", e)
                }
            require(root is JsonObject) { "The tracklist must be a JSON object with \"tracks\"" }
            val entries = root["tracks"]
            require(entries is JsonArray && entries.isNotEmpty()) { "The tracklist needs a non-empty \"tracks\" list" }
            val tracks =
                entries.mapIndexed { index, entry ->
                    require(entry is JsonObject) { "Track ${index + 1} of the tracklist is not an object" }
                    track(index + 1, entry)
                }
            return Tracklist(text(root, "name"), tracks)
        }

        private fun track(
            n: Int,
            entry: JsonObject,
        ): NowPlaying {
            val unknown = entry.keys - FIELDS
            require(unknown.isEmpty()) { "Track $n of the tracklist has unknown fields: $unknown" }
            val title = text(entry, "title")
            val artist = text(entry, "artist")
            require(!title.isNullOrBlank()) { "Track $n of the tracklist has no \"title\"" }
            require(!artist.isNullOrBlank()) { "Track $n of the tracklist has no \"artist\"" }
            return NowPlaying(
                title = title,
                artists = artist,
                album = text(entry, "album")?.takeIf { it.isNotBlank() },
                duration = text(entry, "dur")?.let { duration(n, it) },
                player = Settings.SPOTIFY,
            )
        }

        private fun text(
            o: JsonObject,
            field: String,
        ): String? {
            val value = o[field] ?: return null
            require(value is JsonPrimitive && value.isString) { "\"$field\" in the tracklist must be a string" }
            return value.contentOrNull
        }

        private fun duration(
            n: Int,
            text: String,
        ): Duration {
            val match = DURATION.matchEntire(text.trim())
            require(match != null) { "Track $n of the tracklist has a \"dur\" that is not m:ss: $text" }
            val (minutes, seconds) = match.destructured
            return (minutes.toInt() * SECONDS_PER_MINUTE + seconds.toInt()).seconds
        }

        private val FIELDS = setOf("title", "artist", "album", "dur")
        private val DURATION = Regex("([0-9]{1,3}):([0-5][0-9])")
        private const val SECONDS_PER_MINUTE = 60
    }
}
