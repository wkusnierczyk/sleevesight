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
            // The separator ` ... ` takes characters L to L + 4 after a first highlight of L: 296 puts
            // its last space at 300; 295 puts the second highlight's first word across 300, so the cut
            // falls after the separator; 297 puts the dots across 300, so it falls before.
            for (length in listOf(295, 296, 297)) {
                val first = "Sundown by Gordon Lightfoot ".repeat(10).trim().padEnd(length, 'x')
                val line = lookUp(first, "Thesecondhighlight, which is cut away whole.").evidence.single().text
                assertEquals("$first…", line, "a first highlight of $length")
            }
        }

    @Test
    fun `any Unicode white space is a word boundary`() =
        runTest {
            val highlight = "Sundown by Gordon Lightfoot.\u2028" + "word\u00A0".repeat(35) + "word\u0085".repeat(35)
            val collapsed = highlight.replace(Regex("[\\u00A0\\u0085\\u2028]"), " ").trim()

            val line = lookUp(highlight).evidence.single().text

            val quote = line.removeSuffix("…")
            assertTrue(line.endsWith("…") && quote.none { it.isWhitespace() && it != ' ' }, line)
            assertTrue(collapsed.startsWith(quote), "the line is a verbatim start, white space made plain")
            assertEquals(' ', collapsed[quote.length], "the cut falls between two words")
            assertTrue(quote.length > ExaWebSearch.LINE_CHARACTERS - "word ".length, "${quote.length} characters")
        }

    @Test
    fun `text with no space is cut at 300 characters, never inside a surrogate pair`() {
        assertEquals("x".repeat(300) + "…", ExaWebSearch.cut("x".repeat(400)))
        val emoji = "x".repeat(299) + "\uD83C\uDFB5" + "x".repeat(50)
        assertEquals("x".repeat(299) + "…", ExaWebSearch.cut(emoji))
    }

    @Test
    fun `a cut with no space never splits what a reader sees as one character`() {
        val tail = "x".repeat(50)
        val cases =
            listOf(
                // e and a combining acute accent across 300.
                Triple("a letter and its accent", 299, "e\u0301"),
                // A heart and the variation selector that makes it an emoji.
                Triple("a variation selector", 299, "\u2764\uFE0F"),
                // An ideograph and a variation selector from outside the basic plane, at 300 and 301.
                Triple("a supplementary variation selector", 299, "\u845B\uDB40\uDD00"),
                // A musical note built from a notehead and a combining stem, the stem at 300.
                Triple("a supplementary combining mark", 298, "\uD834\uDD58\uD834\uDD65"),
                // Man, zero-width joiner, woman: 296 to 300.
                Triple("an emoji sequence", 296, "\uD83D\uDC68\u200D\uD83D\uDC69"),
                // Thumbs up and a skin tone, the tone at 300.
                Triple("a skin tone", 298, "\uD83D\uDC4D\uD83C\uDFFD"),
                // The flags of France and Germany: France across 300.
                Triple("a flag", 298, "\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9\uD83C\uDDEA"),
            )
        for ((name, before, cluster) in cases) {
            assertEquals("x".repeat(before) + "…", ExaWebSearch.cut("x".repeat(before) + cluster + tail), name)
        }
        // France at 296 to 299, Germany from 300: the boundary between the two flags is kept.
        val france = "\uD83C\uDDEB\uD83C\uDDF7"
        val flags = "x".repeat(296) + france + "\uD83C\uDDE9\uD83C\uDDEA" + tail
        assertEquals("x".repeat(296) + france + "…", ExaWebSearch.cut(flags))
    }

    @Test
    fun `cached results from before the cut are not served`() {
        // The retrieval cache misses an entry kept under another extraction version
        // (`RetrievalCacheTest`, "a result kept under other extraction rules is a miss"); results
        // kept before #634 were made at version 1, every adapter's default.
        val adapter = ExaWebSearch(ReplayNetClient.ok("{}"), Secret("test-exa-key"), JvmAccentFolder)
        assertEquals(2, adapter.extractionVersion)
    }
}
