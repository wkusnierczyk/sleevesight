package app.sleevesight.core.synthesis

import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.text.APP_DATE_RULE
import app.sleevesight.core.text.US_DATES
import app.sleevesight.core.text.US_DATE_RULE
import app.sleevesight.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The song's, the artist's and the album's prompt (#522): stored, so a change to it is a reviewed diff. */
class ItemPromptTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun stored(name: String): String = Fixtures.text("prompts/$name").removeSuffix("\n")

    @Test
    fun `the stored prompts are version 1's`() {
        // A change to the template changes the stored prompts and raises the version with them (the
        // synthesis cache keys insights by it): update both together. The key insight's version 9
        // (#767) left this prompt as it was: it keeps version 8's rules and style ([ItemPrompt.contract]).
        // The rule on a line found in the track's language (#902) came without a version, the lines'
        // languages being in the evidence fingerprint. Nor did the rule on dates (#957).
        assertEquals(1, ItemPrompt.VERSION)
    }

    @Test
    fun `each asks for dates in the form given, with the style (#957)`() {
        val playing = FixtureEvidence.nowPlaying(1)
        val normalized = normalizer.normalize(playing)
        for (kind in ItemPrompt.KINDS) {
            val subject = ItemPrompt.Subject(kind, "Name")
            val style = { prompt: String ->
                prompt.substringAfter("\nSTYLE\n").substringBefore(InsightPrompt.CONTRACT_HEADING)
            }
            val app = ItemPrompt.build(subject, playing, normalized, FixtureEvidence.of(1), false)
            assertTrue(APP_DATE_RULE in style(app), kind.key)
            val us = ItemPrompt.build(subject, playing, normalized, FixtureEvidence.of(1), false, US_DATES)
            assertTrue(US_DATE_RULE in style(us), kind.key)
            assertFalse(APP_DATE_RULE in us, kind.key)
        }
    }

    @Test
    fun `the song's, model knowledge off`() {
        val playing = FixtureEvidence.nowPlaying(1)
        val normalized = normalizer.normalize(playing)
        val subject = ItemPrompt.Subject(CardKind.SONG, normalized.title)
        assertEquals(
            stored("item-song-1.txt"),
            ItemPrompt.build(subject, playing, normalized, FixtureEvidence.of(1), allowModel = false),
        )
    }

    @Test
    fun `the artist's, model knowledge on`() {
        val playing = FixtureEvidence.nowPlaying(41)
        val normalized = normalizer.normalize(playing)
        val subject = ItemPrompt.Subject(CardKind.ARTIST, normalized.primary)
        assertEquals(
            stored("item-artist-41.txt"),
            ItemPrompt.build(subject, playing, normalized, FixtureEvidence.of(41), allowModel = true),
        )
    }
}
