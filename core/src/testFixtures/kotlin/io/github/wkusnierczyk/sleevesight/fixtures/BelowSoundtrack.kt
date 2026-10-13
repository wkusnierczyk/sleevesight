package io.github.wkusnierczyk.sleevesight.fixtures

import kotlinx.serialization.json.Json

/**
 * The playlist "Below Soundtrack Netflix" from the simulator (`fixtures/below-soundtrack/`): the
 * playlist, the frozen lookups (`facts`) and the release data (`albums`). Facts and albums exist
 * for the first occurrence of each track only; [firstOccurrence] maps a duplicate to it.
 *
 * A JVM module reads it with `testImplementation(testFixtures(project(":core")))`.
 */
object BelowSoundtrack {
    const val DIRECTORY: String = "below-soundtrack"

    private val json = Json { ignoreUnknownKeys = false }

    val playlist: FixturePlaylist by lazy { json.decodeFromString(Fixtures.text("$DIRECTORY/playlist.json")) }

    val facts: List<FixtureFacts> by lazy { json.decodeFromString(Fixtures.text("$DIRECTORY/facts.json")) }

    val albums: List<FixtureAlbum> by lazy { json.decodeFromString(Fixtures.text("$DIRECTORY/albums.json")) }

    val tracks: List<FixtureTrack> get() = playlist.tracks

    /** The playlist entry at position [n], from 1. */
    fun track(n: Int): FixtureTrack = tracks.first { it.n == n }

    /** The position of the first entry with the same title and artist as entry [n]. */
    fun firstOccurrence(n: Int): Int {
        val track = track(n)
        return tracks.first { it.title == track.title && it.artist == track.artist }.n
    }

    /** The frozen lookups for entry [n], through its first occurrence. */
    fun factsFor(n: Int): FixtureFacts = facts.first { it.n == firstOccurrence(n) }

    /** The release data for entry [n], through its first occurrence. */
    fun albumFor(n: Int): FixtureAlbum = albums.first { it.n == firstOccurrence(n) }
}

/** Reads the shared fixtures, which are resources of the `:core` test-fixtures source set. */
object Fixtures {
    /** The text of [path], relative to `fixtures/`, for example `below-soundtrack/playlist.json`. */
    fun text(path: String): String {
        val stream =
            Fixtures::class.java.getResourceAsStream("/$path")
                ?: error("No fixture at fixtures/$path")
        return stream.use { it.readBytes().decodeToString() }
    }
}
