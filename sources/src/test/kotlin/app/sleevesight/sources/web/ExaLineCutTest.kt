package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.fixtures.BelowSoundtrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exa's highlights cut to the 300 characters the request asks for (#634): Exa does not hold to
 * `maxCharacters` (#608), so the adapter cuts each line at a word boundary and marks the cut, and
 * the filter judges the line as cut.
 */
class ExaLineCutTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    // Track 1: Sundown by Gordon Lightfoot.
    private val sundown: NormalizedTrack =
        BelowSoundtrack.track(1).let { normalizer.normalize(it.title, it.artist) }

    private suspend fun lookUp(vararg highlights: String): SourceResult {
        val body =
            buildJsonObject {
                put(
                    "results",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("url", "https://catalogue.example/entry/1974")
                                put("title", "Catalogue entry")
                                put("highlights", buildJsonArray { highlights.forEach { add(it) } })
                            },
                        )
                    },
                )
            }.toString()
        return ExaWebSearch(ReplayNetClient.ok(body), Secret("test-exa-key"), JvmAccentFolder)
            .lookup(sundown, CompletableDeferred(Resolution.NONE))
    }

    @Test
    fun `a 3,000-character highlight is cut at a word boundary to 300 characters and an ellipsis`() =
        runTest {
            val sentence = "The single came out in the spring of 1974 and topped the chart. "
            val highlight = "Sundown by Gordon Lightfoot.\n" + sentence.repeat(47)
            assertTrue(highlight.length >= 3_000, "the highlight is ${highlight.length} characters")
            val collapsed = highlight.replace(Regex("\\s+"), " ").trim()

            val line = lookUp(highlight).evidence.single().text

            assertTrue(line.endsWith("…"), line)
            val quote = line.removeSuffix("…")
            assertTrue(quote.length <= ExaWebSearch.LINE_CHARACTERS, "${quote.length} characters")
            assertTrue(collapsed.startsWith(quote), "the line is a verbatim start of the highlight")
            assertEquals(' ', collapsed[quote.length], "the cut falls between two words")
            // The longest such start: the next word would pass 300 characters.
            assertTrue(collapsed.indexOf(' ', quote.length + 1) > ExaWebSearch.LINE_CHARACTERS)
        }

    @Test
    fun `a result named only in the part cut away is not kept`() =
        runTest {
            val filler = "A catalogue of recordings from the decade, listed by label and year. ".repeat(5)
            assertTrue(filler.length > ExaWebSearch.LINE_CHARACTERS)

            val result = lookUp(filler + "Sundown by Gordon Lightfoot, 1974.")

            assertEquals(Outcome.Rejected(ExaWebSearch.REJECTED), result.outcome)
            assertTrue(result.evidence.isEmpty())
        }

    @Test
    fun `a line within 300 characters is kept whole`() {
        val text = "Sundown by Gordon Lightfoot ".repeat(10).trim().padEnd(ExaWebSearch.LINE_CHARACTERS, 'x')
        assertEquals(ExaWebSearch.LINE_CHARACTERS, text.length)
        assertEquals(text, ExaWebSearch.cut(text))
    }

    @Test
    fun `a cut at the separator between highlights leaves the separator out`() =
        runTest {
            val first = "Sundown by Gordon Lightfoot ".repeat(10).trim().padEnd(296, 'x')
            val line = lookUp(first, "The second highlight, which is cut away whole.").evidence.single().text
            assertEquals("$first…", line)
        }

    @Test
    fun `text with no space is cut at 300 characters, never inside a surrogate pair`() {
        assertEquals("x".repeat(300) + "…", ExaWebSearch.cut("x".repeat(400)))
        val emoji = "x".repeat(299) + "🎵" + "x".repeat(50)
        assertEquals("x".repeat(299) + "…", ExaWebSearch.cut(emoji))
    }
}
