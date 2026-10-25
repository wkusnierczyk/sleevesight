package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionsTest {
    @Test
    fun `tracks are the regression cases by default, all, or positions and ranges`() {
        assertEquals(
            listOf(1, 4, 7, 8, 12, 13, 14, 20, 22, 23, 24, 30, 36, 37, 45, 48, 61, 63, 76, 77),
            Options.parse(listOf("replay")).tracks,
        )
        assertEquals(80, Options.tracks("all").size)
        assertEquals(listOf(1, 12, 20, 21, 22), Options.tracks("12,1,20-22"))
        assertFailsWith<IllegalArgumentException> { Options.tracks("81") }
    }

    @Test
    fun `a tracklist replaces the playlist positions, and not both are given`() {
        val file = File.createTempFile("tracklist", ".json")
        try {
            file.writeText("""{"tracks": [{"title": "Sundown", "artist": "Gordon Lightfoot"}]}""")
            val options = Options.parse(listOf("live", "--tracklist", file.path))
            assertEquals(emptyList(), options.tracks)
            assertEquals(
                "Sundown",
                options.tracklist
                    ?.tracks
                    ?.single()
                    ?.title,
            )
            assertNull(Options.parse(listOf("live")).tracklist)
            assertFailsWith<IllegalArgumentException> {
                Options.parse(listOf("live", "--tracklist", file.path, "--tracks", "1"))
            }
        } finally {
            file.delete()
        }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--tracklist", "no-such-file.json")) }
    }

    @Test
    fun `record runs without the cache and without web search`() {
        val options = Options.parse(listOf("record", "--record", "anthropic", "--cache", "x.db"))
        assertEquals(CacheChoice.None, options.cache)
        assertFalse(options.web)
        assertEquals(setOf("anthropic"), options.record)
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--record", "web")) }
    }

    @Test
    fun `the AI service is Anthropic unless another is given, and Google can be recorded`() {
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("live")).provider)
        assertEquals(ProviderId.GOOGLE, Options.parse(listOf("live", "--provider", "google")).provider)
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--provider", "ollama")) }
        assertEquals(setOf("google"), Options.parse(listOf("record", "--record", "google")).record)
    }

    @Test
    fun `recording an AI service runs that service, and --provider must agree`() {
        assertEquals(ProviderId.GOOGLE, Options.parse(listOf("record", "--record", "wikidata,google")).provider)
        assertEquals(
            ProviderId.GOOGLE,
            Options.parse(listOf("record", "--record", "google", "--provider", "google")).provider,
        )
        val mismatch =
            assertFailsWith<IllegalArgumentException> {
                Options.parse(listOf("record", "--record", "google", "--provider", "anthropic"))
            }
        assertEquals(
            "--record google records google's replies: give --provider google, or leave --provider out",
            mismatch.message,
        )
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--record", "anthropic,google")) }
        // Outside record, --record is ignored, as before.
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("live", "--record", "google")).provider)
        assertEquals("google", Options.nameOf(ProviderId.GOOGLE))
    }

    @Test
    fun `a run without insights says why`() {
        fun off(vararg args: String) = Cli.insightsOff(Options.parse(args.toList()))
        assertEquals("off: --record does not name anthropic", off("record", "--record", "wikidata"))
        assertEquals("off: no SLEEVESIGHT_GOOGLE_API_KEY", off("record", "--record", "google"))
        assertEquals("off: no SLEEVESIGHT_GOOGLE_API_KEY", off("live", "--provider", "google"))
        assertEquals("off: no recorded replies", off("replay"))
        assertEquals("off: --no-insights", off("record", "--record", "wikidata", "--no-insights"))
    }

    @Test
    fun `artist facts are told once a pass, unless --artist-facts-every-track is given`() {
        assertTrue(Options.parse(listOf("replay")).artistFactsOnce)
        assertFalse(Options.parse(listOf("live", "--artist-facts-every-track")).artistFactsOnce)
    }

    @Test
    fun `the AI service is Anthropic unless given, and OpenAI only for live`() {
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("replay")).provider)
        assertEquals(ProviderId.OPENAI, Options.parse(listOf("live", "--provider", "openai")).provider)
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("record", "--provider", "anthropic")).provider)
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("replay", "--provider", "openai")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--provider", "openai")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--provider", "ollama")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--record", "openai")) }
        // Google's replies are recorded and replayed, as Anthropic's are.
        assertEquals(ProviderId.GOOGLE, Options.parse(listOf("replay", "--provider", "google")).provider)
    }

    @Test
    fun `the cache is temporary for replay and kept for live, unless given`() {
        assertEquals(CacheChoice.Temporary, Options.parse(listOf("replay")).cache)
        assertEquals(CacheChoice.At(File(".dev/cli/cache.db")), Options.parse(listOf("live")).cache)
        assertEquals(CacheChoice.None, Options.parse(listOf("live", "--cache", "none")).cache)
        assertFalse(Options.parse(listOf("live", "--no-web")).web)
    }

    @Test
    fun `a run is repeated with --repeat, once by default, and not when recording`() {
        assertEquals(1, Options.parse(listOf("live")).repeat)
        assertEquals(3, Options.parse(listOf("live", "--repeat", "3")).repeat)
        assertEquals(2, Options.parse(listOf("replay", "--repeat", "2")).repeat)
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--repeat", "0")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--repeat", "three")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--repeat", "2")) }
        assertEquals(1, Options.parse(listOf("record", "--repeat", "1")).repeat)
    }
}
