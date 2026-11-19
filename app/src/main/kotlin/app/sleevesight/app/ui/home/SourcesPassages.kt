package app.sleevesight.app.ui.home

import app.sleevesight.core.sources.WebPage
import java.util.Locale

// The passages of the web pages *Sources* lists (#1434): a page that repeats a line already listed
// is left out, and a passage that starts mid-sentence is shown from a sentence's start.

/**
 * [pages], whose texts are passages of them (Exa's highlights), without those that repeat another
 * (#1434): a page whose passage one of [listed] (the texts of the lines already shown) holds, or
 * all but holds ([repeats]), or that of a longer page of [pages] does (iHeart's page, AllMusic's
 * biography again, as a fragment of it). The longer page is the one kept; of two alike, the first.
 * The pages kept stay in their order.
 */
internal fun withoutRepeats(
    pages: List<WebPage>,
    listed: List<String>,
): List<WebPage> {
    val kept = mutableListOf<WebPage>()
    pages.sortedByDescending { words(it.text).size }.forEach { page ->
        if ((listed + kept.map { it.text }).none { repeats(page.text, it) }) kept += page
    }
    return pages.filter { page -> kept.any { it === page } }
}

/**
 * Whether [passage] repeats [line]: its words found in [line]'s, in order, or nearly all its runs of
 * three words ([SHARED] of them) found in [line] (the same text, a word or a mark apart). Case and
 * punctuation aside. An empty passage repeats nothing.
 */
internal fun repeats(
    passage: String,
    line: String,
): Boolean {
    val words = words(passage)
    val others = words(line)
    val whole = words.isNotEmpty() && " ${others.joinToString(" ")} ".contains(" ${words.joinToString(" ")} ")
    val runs = runs(words)
    val theirs = runs(others).toSet()
    return whole || (runs.size >= MIN_RUNS && runs.count { it in theirs } >= runs.size * SHARED)
}

/**
 * [text], a page's passage, as *Sources* shows it (#1434): one that starts mid-sentence (a small
 * letter, or a mark such as a comma, before its first word) from the next sentence's start, else
 * from its first capital letter, with "…" in front; as it is otherwise, or with no capital letter
 * to start at.
 */
internal fun shownPassage(text: String): String {
    val passage = text.trim()
    val first = passage.indexOfFirst { it.isLetterOrDigit() }
    val midSentence =
        first >= 0 &&
            (passage[first].isLowerCase() || passage.take(first).any { it !in OPENERS && !it.isWhitespace() })
    val start =
        if (midSentence) {
            val sentence = SENTENCE.find(passage, first)?.groups[1]
            sentence?.range?.first ?: CAPITAL.find(passage, first)?.range?.first
        } else {
            null
        }
    return start?.let { "$ELLIPSIS ${passage.substring(it)}" } ?: passage
}

// The words of [text], in small letters, without its punctuation.
private fun words(text: String): List<String> =
    text
        .lowercase(Locale.ROOT)
        .split(NOT_WORD)
        .filter { it.isNotEmpty() }

// [words]' runs of three, one from each word on.
private fun runs(words: List<String>): List<List<String>> = words.windowed(RUN)

private const val RUN = 3

// A passage with fewer runs is too short to be read as nearly the same: it repeats a line only whole.
private const val MIN_RUNS = 3

// The share of a passage's runs a line must hold for the passage to repeat it.
private const val SHARED = 0.8

private const val ELLIPSIS = "…"

// What may come before a sentence's first word, spaces aside: opening quotes and brackets, an ellipsis.
private const val OPENERS = "\"'“‘«([¿¡…."

// A run of what is no letter nor digit; classes ICU and the JVM read alike.
private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

// A sentence's end, then its next one's start: a capital letter, perhaps after an opening quote.
private val SENTENCE = Regex("[.!?…][\"'”’»)]?\\s+[\"'“‘«(]?(\\p{Lu})")

private val CAPITAL = Regex("\\p{Lu}")
