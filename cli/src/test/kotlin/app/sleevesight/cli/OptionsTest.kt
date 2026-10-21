package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun `the AI service is Anthropic unless given, and OpenAI only for live`() {
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("replay")).provider)
        assertEquals(ProviderId.OPENAI, Options.parse(listOf("live", "--provider", "openai")).provider)
        assertEquals(ProviderId.ANTHROPIC, Options.parse(listOf("record", "--provider", "anthropic")).provider)
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("replay", "--provider", "openai")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("record", "--provider", "openai")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("live", "--provider", "ollama")) }
    }

    @Test
    fun `the cache is temporary for replay and kept for live, unless given`() {
        assertEquals(CacheChoice.Temporary, Options.parse(listOf("replay")).cache)
        assertEquals(CacheChoice.At(File(".dev/cli/cache.db")), Options.parse(listOf("live")).cache)
        assertEquals(CacheChoice.None, Options.parse(listOf("live", "--cache", "none")).cache)
        assertFalse(Options.parse(listOf("live", "--no-web")).web)
    }
}
