package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A line found in the track's language (#902) is marked with the language inside its parentheses,
 * and every prompt that holds evidence lines says such lines are translated into the answer's
 * language and cited as usual.
 */
class TranslationPromptTest {
    private val at = Instant.parse("2026-11-05T12:00:00Z")

    private val hindi =
        EvidenceSet.of(
            listOf(
                EvidenceDraft(SourceId.MUSICBRAINZ, Scope.SONG, "“Tum Hi Ho” was written by Mithoon.", null, at),
                EvidenceDraft(
                    SourceId.WIKIPEDIA,
                    Scope.RELEASE,
                    "आशिक़ी 2 मोहित सूरी द्वारा निर्देशित 2013 की बॉलीवुड संगीतमय प्रेमपरक नाटक फ़िल्म है।",
                    "https://hi.wikipedia.org/wiki/%E0%A4%86",
                    at,
                    language = "hi",
                ),
                EvidenceDraft(SourceId.WEB, Scope.WEB, "Una canción.", "https://example.org", at, language = "es"),
            ),
            JvmHasher,
        )

    @Test
    fun `a line in a language names it after its scope, an English line as before`() {
        assertEquals(
            "[MB1] (MusicBrainz, song) “Tum Hi Ho” was written by Mithoon.",
            InsightPrompt.evidenceLine(hindi.lines[0]),
        )
        assertEquals(
            "[W1] (Wikipedia, release, Hindi) आशिक़ी 2 मोहित सूरी द्वारा निर्देशित " +
                "2013 की बॉलीवुड संगीतमय प्रेमपरक नाटक फ़िल्म है।",
            InsightPrompt.evidenceLine(hindi.lines[1]),
        )
        assertEquals("[S1] (Web search, web, Spanish) Una canción.", InsightPrompt.evidenceLine(hindi.lines[2]))
    }

    @Test
    fun `a code with no language known is named by its code`() {
        val line = hindi.lines[2].copy(language = "xx")
        assertEquals("[S1] (Web search, web, xx) Una canción.", InsightPrompt.evidenceLine(line))
    }

    @Test
    fun `the rule says such lines are translated and cited as usual`() {
        assertEquals(
            "- A line marked with a language, like (Wikipedia, release, Hindi), is in that language: translate " +
                "what you use of it into English, and cite it as usual.",
            InsightPrompt.TRANSLATION_RULE,
        )
    }

    @Test
    fun `every prompt with evidence lines holds the rule, with or without the model's knowledge`() {
        val normalizer = Normalizer(JvmAccentFolder)
        val playing = NowPlaying(title = "Tum Hi Ho", artists = "Arijit Singh", album = "Aashiqui 2", player = "p")
        val normalized = normalizer.normalize(playing)
        for (allowModel in listOf(false, true)) {
            assertTrue(InsightPrompt.TRANSLATION_RULE in InsightPrompt.system(allowModel))
            assertTrue(InsightPrompt.TRANSLATION_RULE in GenreReasonPrompt.system(allowModel))
            for (kind in ItemPrompt.KINDS) {
                val prompt =
                    ItemPrompt.build(
                        ItemPrompt.Subject(kind, "Tum Hi Ho"),
                        playing,
                        normalized,
                        hindi,
                        allowModel,
                    )
                assertTrue(InsightPrompt.TRANSLATION_RULE in prompt, kind.key)
                assertTrue("[W1] (Wikipedia, release, Hindi) " in prompt, kind.key)
            }
        }
        assertTrue("[W1] (Wikipedia, release, Hindi) " in InsightPrompt.user(playing, normalized, hindi))
        assertTrue("[W1] (Wikipedia, release, Hindi) " in GenreReasonPrompt.user(playing, normalized, hindi, "Filmi"))
    }

    @Test
    fun `the prompts' versions were raised with the rule`() {
        assertEquals(12, InsightPrompt.VERSION)
        assertEquals(2, ItemPrompt.VERSION)
        assertEquals(7, GenreReasonPrompt.VERSION)
    }
}
