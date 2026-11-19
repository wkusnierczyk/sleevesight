package app.sleevesight.app.ui.home

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.sameAddress
import java.util.Locale

// The passages of the web pages *Sources* lists (#1434): a page whose passage a line already listed
// holds whole is left out, its citations shown as that line's, and a passage that starts
// mid-sentence is shown whole, after "…".

/**
 * [pages], whose texts are passages of them (Exa's highlights), without those another line holds
 * whole ([kept]), and, for each left out, the line that holds it ([repeated]).
 */
internal class Passages(
    val kept: List<WebPage>,
    val repeated: List<Repeated>,
)

/**
 * A page left out as a repeat (#1434), [page], and what holds its passage: a line already listed,
 * [line] its id, or a longer page kept, [within].
 */
internal class Repeated(
    val page: WebPage,
    val line: String? = null,
    val within: WebPage? = null,
)

/**
 * A page left out as a repeat (#1434), by its address, [url], and its passage as [passageKey] reads
 * it, [passage]: one address may give two passages (a genre's highlight and the vocals check's), each
 * held by another line.
 */
internal data class MovedPage(
    val url: String,
    val passage: String,
)

/** The pages left out as repeats (#1434), each with the id of the line that holds its passage whole. */
internal typealias Moved = Map<MovedPage, String>

/**
 * The id of the line that holds the passage [passage] of the page at [url] (`sameAddress`), left out
 * as a repeat (#1434); null for a passage unknown, or one no line holds for this address.
 */
internal fun Moved.holderOf(
    url: String,
    passage: String?,
): String? {
    val key = passage?.let(::passageKey)?.takeIf { it.isNotEmpty() } ?: return null
    return entries.firstOrNull { it.key.passage == key && sameAddress(it.key.url, url) }?.value
}

/** [text], a passage, as repeats are read: its words, case, punctuation and spaces aside. */
internal fun passageKey(text: String): String = words(text).joinToString(" ")

/**
 * [pages], whose texts are passages of them (Exa's highlights), without those that repeat another
 * (#1434): a page whose passage, case, punctuation and spaces aside ([repeats]), one of [listed]
 * (the lines already shown) holds whole, or a longer page of [pages] does (iHeart's page, AllMusic's
 * biography again, as a fragment of it). The longer page is the one kept; of two alike, the first.
 * Only the whole passage counts: one a word apart ("not", another year) is kept. The pages kept stay
 * in their order.
 */
internal fun withoutRepeats(
    pages: List<WebPage>,
    listed: List<EvidenceLine>,
): Passages {
    val kept = mutableListOf<WebPage>()
    val repeated = mutableListOf<Repeated>()
    pages.sortedByDescending { words(it.text).size }.forEach { page ->
        val line = listed.firstOrNull { repeats(page.text, it.text) }
        val within = kept.firstOrNull { repeats(page.text, it.text) }
        when {
            line != null -> repeated += Repeated(page, line = line.id)
            within != null -> repeated += Repeated(page, within = within)
            else -> kept += page
        }
    }
    return Passages(pages.filter { page -> kept.any { it === page } }, repeated)
}

/**
 * Whether [passage] repeats [line]: its words found in [line]'s, all of them, in order, one after
 * another; case, punctuation and spaces aside. An empty passage repeats nothing.
 */
internal fun repeats(
    passage: String,
    line: String,
): Boolean {
    val words = words(passage)
    return words.isNotEmpty() && " ${words(line).joinToString(" ")} ".contains(" ${words.joinToString(" ")} ")
}

/**
 * [text], a page's passage, as *Sources* shows it (#1434): one that starts mid-sentence (a small
 * letter, or a mark such as a comma, before its first word) with every word of it, the marks and
 * spaces before its first word (an opening quote or bracket aside) left out and "… " in front; as it
 * is otherwise.
 */
internal fun shownPassage(text: String): String {
    val passage = text.trim()
    // Read by code point, so a letter beyond the first plane (𝔱, a surrogate pair) is a letter.
    val first = passage.firstAt(::wordy)
    val midSentence =
        first >= 0 &&
            (
                Character.isLowerCase(passage.codePointAt(first)) ||
                    passage.take(first).any { it !in OPENERS && !it.isWhitespace() }
            )
    if (!midSentence) return passage
    val start = passage.firstAt { wordy(it) || it in OPENING_CODES }
    return "$ELLIPSIS ${passage.substring(start)}"
}

// The index of the first code point of this that [test] holds for; -1 for none.
private fun String.firstAt(test: (Int) -> Boolean): Int {
    var index = 0
    while (index < length) {
        val code = codePointAt(index)
        if (test(code)) return index
        index += Character.charCount(code)
    }
    return -1
}

// Whether [code] is of a word, as [words] reads them: a letter, a mark (a Hindi vowel sign), a digit.
private fun wordy(code: Int): Boolean = Character.isLetterOrDigit(code) || Character.getType(code) in MARKS

private val MARKS =
    setOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )

// The words of [text], in small letters, without its punctuation; its letters' marks kept.
private fun words(text: String): List<String> =
    text
        .lowercase(Locale.ROOT)
        .split(NOT_WORD)
        .filter { it.isNotEmpty() }

private const val ELLIPSIS = "…"

// What opens a sentence before its first word: an opening quote or bracket, kept when trimming.
private const val OPENING_MARKS = "\"'“‘«([¿¡"

private val OPENING_CODES = OPENING_MARKS.map { it.code }.toSet()

// What may come before a sentence's first word, spaces aside: an opening quote or bracket, an ellipsis.
private const val OPENERS = "$OPENING_MARKS…."

// A run of what is no letter, mark nor digit (a Hindi vowel sign is a mark: रीमा is not राम); classes
// ICU and the JVM read alike.
private val NOT_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
