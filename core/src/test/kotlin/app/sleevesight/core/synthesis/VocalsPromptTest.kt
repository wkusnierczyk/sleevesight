package app.sleevesight.core.synthesis

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.WebPage
import app.sleevesight.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The check of a track's vocals' prompt (#998), version 1: *Once*'s and *Up to 3 times*' pinned as
 * stored (`prompts/vocals-check-*.txt`; frozen in `design/prompts/vocals-check-v1-*.txt`), the track
 * as the player and the normalizer give it and nothing the sources found, and *Once*'s query.
 */
class VocalsPromptTest {
    private val playing =
        NowPlaying(title = "Nasnusa", artists = "Sababa 5, Yurika Hanashima", album = "Kokoro", player = "p")
    private val track = Normalizer(JvmAccentFolder).normalize(playing)
    private val page = WebPage("https://bandcamp.example/nasnusa", "Nasnusa", "Nasnusa, sung by Yurika Hanashima.")

    private fun stored(name: String): String = Fixtures.text("prompts/$name").removeSuffix("\n")

    @Test
    fun `Once's prompt is the track and the pages its search found, as stored`() {
        assertEquals(stored("vocals-check-once.txt"), VocalsPrompt.once(playing, track, listOf(page)))
    }

    @Test
    fun `Up to 3 times' prompt has the model search, as stored`() {
        assertEquals(stored("vocals-check-agent.txt"), VocalsPrompt.agent(playing, track, 3))
    }

    @Test
    fun `Once's query is the title and artists with words that settle the vocals`() {
        val query = VocalsPrompt.query(track)
        assertEquals("\"Nasnusa\" by Sababa 5, Yurika Hanashima, instrumental or vocals, lyrics, featuring", query)
        assertFalse("Kokoro" in query)
        assertTrue(VocalsPrompt.agent(playing, track, 3).contains("Album: Kokoro"))
    }
}
