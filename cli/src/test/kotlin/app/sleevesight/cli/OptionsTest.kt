package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
    fun `the cache is temporary for replay and kept for live, unless given`() {
        assertEquals(CacheChoice.Temporary, Options.parse(listOf("replay")).cache)
        assertEquals(CacheChoice.At(File(".dev/cli/cache.db")), Options.parse(listOf("live")).cache)
        assertEquals(CacheChoice.None, Options.parse(listOf("live", "--cache", "none")).cache)
        assertFalse(Options.parse(listOf("live", "--no-web")).web)
    }
}
