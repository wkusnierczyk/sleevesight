package io.github.wkusnierczyk.sleevesight.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
    fun `a bad command line is a usage error`() {
        assertEquals(Cli.EXIT_USAGE, run("replay", "--frobnicate").first)
        assertEquals(Cli.EXIT_USAGE, run().first)
    }
}
