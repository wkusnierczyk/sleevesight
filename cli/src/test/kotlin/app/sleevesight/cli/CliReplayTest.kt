package app.sleevesight.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI end to end over the recorded responses, as CI runs it: no network, no keys. The gate's
 * collision criterion is an assertion here, through the exit status.
 */
class CliReplayTest {
    private val fixtures = System.getProperty("sleevesight.fixtures")

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val status = Cli(PrintStream(out, true), PrintStream(ByteArrayOutputStream()), emptyMap()).run(args.toList())
        return status to out.toString(Charsets.UTF_8)
    }

    @Test
    fun `the regression tracks replay, and the collisions are rejected or correctly resolved`() {
        val (status, report) = run("replay", "--fixtures", fixtures)

        assertEquals(0, status, report)
        assertTrue("| Every listed same-name collision rejected or correctly resolved | PASS |" in report, report)
        assertFalse("Requests with no recording" in report)
        // Duplicates in the playlist (22 is 8, 24 is 4) are served from the cache.
        assertTrue("source     peleboy|we in here: W rejected, 0 cited, 0 unverified, 1 rejected" in report)
        assertTrue(Regex("source {5}peleboy\\|we in here: W rejected[^\\n]*cache hit").containsMatchIn(report), report)
        // The artist cache's reads (#238): no two regression tracks share an artist MusicBrainz
        // resolves, so the Wikipedia artist articles are all misses here; Last.fm, off in replay,
        // is where the regression tracks share artists by name (Peleboy, Reem).
        val artistCache = Regex("Artist cache: wikipedia\\.artist 0 hits, [1-9][0-9]* misses\\.")
        assertTrue(artistCache.containsMatchIn(report), report)
        // Sources the CLI cannot run are reported as off, with the reason.
        assertTrue("| Last.fm | no | off: no Last.fm key exists yet" in report)
        assertTrue("| Web search | no | off: its fixtures are synthetic" in report)
    }

    @Test
    fun `a request with no recording fails the run`() {
        // Track 2 (Whail) was never recorded.
        val (status, report) = run("replay", "--fixtures", fixtures, "--tracks", "2", "--no-insights")

        assertEquals(Cli.EXIT_USAGE, status)
        assertTrue("Requests with no recording" in report)
    }

    @Test
    fun `an unreachable source on a listed collision track leaves the gate unchecked, and the run fails`() {
        // A copy of the fixtures in which Wikipedia answers 503 to its lookups for tracks 8 and 12.
        val copy = Files.createTempDirectory("fixtures").toFile()
        try {
            File(fixtures).copyRecursively(copy)
            val index = File(copy, "http/wikipedia/index.json")
            val broken =
                listOf("query-we-in-here-peleboy", "query-bad-energy-reem").fold(index.readText()) { text, file ->
                    text.replace(Regex("\"status\": 200,(\\s*\"file\": \"$file)"), "\"status\": 503,$1")
                }
            index.writeText(broken)
            for (track in listOf("8", "12")) {
                val (status, report) = run("replay", "--fixtures", copy.path, "--tracks", track, "--no-insights")
                assertEquals(Cli.EXIT_UNCHECKED, status, report)
                assertTrue(
                    "| Every listed same-name collision rejected or correctly resolved | NOT_CHECKED | unchecked:" in
                        report,
                )
            }
        } finally {
            copy.deleteRecursively()
        }
    }

    @Test
    fun `the report measures the artist-level evidence per track (#239)`() {
        val (status, report) = run("replay", "--fixtures", fixtures, "--tracks", "1", "--no-insights")

        assertEquals(0, status, report)
        assertTrue("## Artist-level evidence (#239)" in report, report)
        // Track 1's evidence holds Wikipedia's and Wikidata's lines about Gordon Lightfoot.
        assertTrue(Regex("\\| 1 \\| [0-9]+ \\| [1-9][0-9]* \\(").containsMatchIn(report), report)
    }

    @Test
    fun `a tracklist runs in its order, numbered from 1, with the playlist's collisions unchecked`() {
        // Playlist positions 7 and 1 as a player reports them, so their recordings replay.
        val file = Files.createTempFile("tracklist", ".json").toFile()
        try {
            file.writeText(
                """
                {"name": "Two recorded tracks", "tracks": [
                  {"title": "Fisherman's Blues - 2006 Remaster", "artist": "The Waterboys",
                   "album": "${Cli.nowPlaying(7).album}", "dur": "4:26"},
                  {"title": "Sundown", "artist": "Gordon Lightfoot", "album": "${Cli.nowPlaying(1).album}",
                   "dur": "3:33"}
                ]}
                """.trimIndent(),
            )
            val (status, report) =
                run("replay", "--fixtures", fixtures, "--tracklist", file.path, "--no-insights")

            assertEquals(0, status, report)
            assertFalse("Requests with no recording" in report, report)
            assertTrue("Tracks: 2. Tracklist: Two recorded tracks." in report, report)
            assertTrue("### 1. Fisherman's Blues - 2006 Remaster, The Waterboys" in report, report)
            assertTrue("### 2. Sundown, Gordon Lightfoot" in report, report)
            assertTrue("| NOT_CHECKED | a tracklist run: the collisions are playlist positions |" in report, report)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a bad command line is a usage error`() {
        assertEquals(Cli.EXIT_USAGE, run("replay", "--frobnicate").first)
        assertEquals(Cli.EXIT_USAGE, run().first)
        assertEquals(Cli.EXIT_USAGE, run("replay", "--tracklist", "no-such-file.json").first)
    }
}
