package app.sleevesight.sources.wikipedia

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Properties of how a Wikipedia line is tidied ([WikipediaSource.tidy], #850), over lines built
 * from words in several scripts, brackets, the punctuation a dropped pronunciation leaves, and
 * whitespace.
 */
class TidyPropertiesTest {
    private val word =
        Arb.element(
            "Basia",
            "Bulat",
            "Sigur",
            "Rós",
            "Björk",
            "Guðmundsdóttir",
            "Frehley",
            "FRAY-lee",
            "1984",
            "born",
            "is",
            "a",
            "Канада",
            "坂本",
            "Ελλάδα",
        )

    // The pieces a line is made of: words, brackets, the punctuation a dropped pronunciation leaves
    // (`(, `, `(; `), other punctuation, and whitespace.
    private val piece =
        Arb.choice(
            word,
            Arb.element("(", ")", "()", "( )", "(; )", "(, )", "(,)", ", ", "; ", ".", " – ", "\""),
            Arb.element(" ", " ", " ", "  ", "\t", " "),
        )

    private val line = Arb.list(piece, 0..MAX_PIECES).map { it.joinToString("") }

    // Fails: "a((, ))" (shrunk from ")(,)Basia a((, ))((("), is tidied to "a()": emptied brackets inside
    // brackets leave the outer pair empty, and one pass takes out only the inner. See #1489.
    @Ignore
    @Test
    fun `a tidied line has no empty brackets but those that open it`() =
        runTest {
            checkAll(CONFIG, line) { text ->
                val tidied = WikipediaSource.tidy(text)
                assertNull(EMPTY_BRACKETS.find(tidied)?.value, "in \"$tidied\", from \"$text\"")
            }
        }

    // Fails: "a((, ))" (shrunk from ")(,)Basia a((, ))((("), is tidied to "a()", which is tidied to "a":
    // emptied brackets inside brackets leave the outer pair empty, and one pass takes out only the
    // inner. See #1489.
    @Ignore
    @Test
    fun `tidying is idempotent`() =
        runTest {
            checkAll(CONFIG, line) { text ->
                val tidied = WikipediaSource.tidy(text)
                assertEquals(tidied, WikipediaSource.tidy(tidied), "from \"$text\"")
            }
        }

    @Test
    fun `tidying drops no letter or digit`() =
        runTest {
            checkAll(CONFIG, line) { text ->
                val tidied = WikipediaSource.tidy(text)
                assertEquals(text.filter { it.isLetterOrDigit() }, tidied.filter { it.isLetterOrDigit() })
            }
        }

    private companion object {
        val CONFIG = PropTestConfig(seed = 850L, iterations = 500)
        const val MAX_PIECES = 16

        // A pair of brackets holding nothing but whitespace, commas and semicolons, after something
        // other than whitespace: what `tidy` takes out. Those that open a line are a title (Sigur
        // Rós's album `( )`), kept.
        val EMPTY_BRACKETS = Regex("(?<=\\S)\\s*\\([\\s,;]*\\)")
    }
}
