package app.sleevesight.core.text

/**
 * Text from players and sources as a person reading it sees it (#1041, from #217): what the app takes
 * from outside, before it reaches a prompt, *Sources*, a genre's quote or a report. One place, so every
 * way in is cleaned alike; each step is a function of the text, applied in [STEPS]' order, so a new one
 * goes in the list.
 *
 * - [withoutLoneSurrogates]: a lone UTF-16 surrogate becomes U+FFFD first, so dropping a character
 *   between two cannot join them into a new character (a tag's).
 * - [withPlainAngles]: the fullwidth and small angle signs (`＜＞`, `﹤﹥`) become `<` and `>`, so a prompt
 *   escapes them as it does `<` and nothing looks like the tag that closes a fence; both halves of each
 *   pair, so a title in brackets still reads as one. Before the next step, which then reads a joiner
 *   beside one as beside ASCII, so cleaning again changes nothing.
 * - [withoutInvisible]: Unicode format characters (category `Cf`) are dropped: the bidirectional
 *   controls, the zero-width spaces, the word joiner, the byte-order mark, the tag block
 *   (U+E0000-E007F). A model reads them, a person sees nothing, so text could say to a model what a
 *   person checking it cannot find. The joiners U+200C and U+200D stay where writing needs them: one
 *   alone between two letters, marks or symbols outside ASCII (an emoji sequence, Persian's
 *   non-joiner, a conjunct in an Indic script), and U+200D after a virama even at a word's end
 *   (Malayalam's chillus written the old way). A joiner beside ASCII, a space, a private-use or
 *   unassigned character, or in a run, is dropped. So are the other characters that show as nothing
 *   ([DROPPED]: the combining grapheme joiner, the Hangul fillers), and every variation selector but
 *   the first after a character kept ([SELECTORS]; an ideographic one only after an ideograph), so a
 *   run of them cannot carry a message after an emoji. Which characters are format characters is
 *   the app's own table ([FORMATS]), not the platform's Unicode data: an older Android (the app runs
 *   from Android 8) knows fewer of them, so the phone drops what the JVM drops.
 *
 * No regex: a scan by code point, the same on the JVM and on Android.
 */
@Suppress("TooManyFunctions") // One for each step and each rule of the scan, and the two helpers callers share.
object SourceText {
    private val STEPS: List<(String) -> String> =
        listOf({ it.withoutLoneSurrogates() }, ::withPlainAngles, ::withoutInvisible)

    /** [text] through each step of the cleaning, in order. */
    fun clean(text: String): String = STEPS.fold(text) { acc, step -> step(acc) }

    /**
     * [title], a page's or a player's, as a person sees it ([clean]) and trimmed; null when nothing is
     * left. Where a title from outside is shown or kept, so a step added to the cleaning reaches it.
     */
    fun titleOf(title: String?): String? = title?.let(::clean)?.trim()?.takeIf { it.isNotEmpty() }

    /** [text] cut to at most [max] characters (UTF-16 units), never inside a surrogate pair. */
    fun cut(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    /**
     * [text] without the characters a person does not see ([SourceText]): each is decided by the
     * characters kept before it, so cleaning again keeps what was kept.
     */
    internal fun withoutInvisible(text: String): String {
        if (text.none { it.isSurrogate() || looked(it.code) }) return text
        val out = StringBuilder(text.length)
        var i = 0
        var last = -1
        var beforeLast = -1
        var previousKept = false
        while (i < text.length) {
            val point = text.codePointAt(i)
            val next = i + Character.charCount(point)
            val following = if (next < text.length) text.codePointAt(next) else -1
            previousKept = kept(point, last, beforeLast, previousKept, following)
            if (previousKept) {
                out.appendCodePoint(point)
                beforeLast = last
                last = point
            }
            i = next
        }
        return out.toString()
    }

    /** [text] with the fullwidth and small angle signs as `<` and `>`. */
    internal fun withPlainAngles(text: String): String {
        if (text.none { it in ANGLES }) return text
        return buildString(text.length) { text.forEach { append(ANGLES[it] ?: it) } }
    }

    // Whether [point] stays, after [last] and [beforeLast], the characters kept before it (-1: none),
    // [previousKept] when the character just before it was kept, and before [following] (-1 at the end).
    private fun kept(
        point: Int,
        last: Int,
        beforeLast: Int,
        previousKept: Boolean,
        following: Int,
    ): Boolean =
        when {
            point in DROPPED -> false
            isSelector(point) -> selectorKept(point, last, previousKept)
            !isFormat(point) -> true
            point !in JOINERS || !previousKept -> false
            joinsBefore(last) && joinsAfter(following) -> true
            else -> point == ZERO_WIDTH_JOINER && afterVirama(last, beforeLast)
        }

    // A variation selector stays only as the first after a character kept, and an ideographic one
    // only after an ideograph: a run of them, or one alone, can carry a message no one sees.
    private fun selectorKept(
        point: Int,
        last: Int,
        previousKept: Boolean,
    ): Boolean {
        if (!previousKept || isSelector(last)) return false
        return point !in IDEOGRAPHIC_SELECTORS || Character.isIdeographic(last)
    }

    // A virama, a non-spacing mark after a letter, before which a joiner makes the half form a
    // script needs even at a word's end (Malayalam's chillus written the old way).
    private fun afterVirama(
        last: Int,
        beforeLast: Int,
    ): Boolean =
        Character.getType(last) == Character.NON_SPACING_MARK.toInt() &&
            !isSelector(last) &&
            Character.isLetter(beforeLast)

    private fun isFormat(point: Int): Boolean = point >= FIRST_FORMAT && FORMATS.any { point in it }

    /**
     * Every format character (category `Cf`) of Unicode 15.1, by code point: the table the app reads
     * instead of the platform's Unicode data, which on an older Android leaves some of them
     * unassigned (U+110CD, U+13430 to U+1343F). A test checks it against the JVM's data, so a newer
     * Unicode's additions are listed here as the build's JDK learns them.
     */
    internal val FORMATS: List<IntRange> =
        ranges(
            "00AD 0600-0605 061C 06DD 070F 0890-0891 08E2 180E 200B-200F 202A-202E 2060-2064 2066-206F " +
                "FEFF FFF9-FFFB 110BD 110CD 13430-1343F 1BCA0-1BCA3 1D173-1D17A E0001 E0020-E007F",
        )

    private val FIRST_FORMAT = FORMATS.first().first

    /** The variation selectors: Mongolian's, the standard ones (VS1 to VS16) and the ideographic ones. */
    internal val SELECTORS: List<IntRange> = ranges("180B-180D 180F FE00-FE0F E0100-E01EF")

    private val IDEOGRAPHIC_SELECTORS = ranges("E0100-E01EF").single()

    /**
     * Characters that are no format characters but show as nothing (`Default_Ignorable_Code_Point`):
     * the combining grapheme joiner and the Hangul fillers. Always dropped.
     */
    internal val DROPPED: Set<Int> = ranges("034F 115F-1160 3164 FFA0").flatMap { it.toList() }.toSet()

    private fun isSelector(point: Int): Boolean = SELECTORS.any { point in it }

    // Whether [point] is one the scan may drop.
    private fun looked(point: Int): Boolean = isFormat(point) || isSelector(point) || point in DROPPED

    // Whether a joiner after [point], kept, may join: a letter, a mark or a symbol outside ASCII.
    private fun joinsBefore(point: Int): Boolean = point > ASCII_END && Character.getType(point) in JOINABLE

    // Whether a joiner before [point] may join: as [joinsBefore], and one that always stays.
    private fun joinsAfter(point: Int): Boolean =
        joinsBefore(point) && !isSelector(point) && point !in DROPPED && !isFormat(point)

    private val JOINABLE: Set<Int> =
        setOf(
            Character.UPPERCASE_LETTER,
            Character.LOWERCASE_LETTER,
            Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER,
            Character.OTHER_LETTER,
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.OTHER_SYMBOL,
            Character.MODIFIER_SYMBOL,
        ).map { it.toInt() }.toSet()

    // [spec]'s code points: hex, single or as `first-last`, separated by spaces.
    private fun ranges(spec: String): List<IntRange> =
        spec.split(' ').map { range ->
            val (first, last) = range.split('-').let { it.first() to it.last() }
            first.toInt(HEX)..last.toInt(HEX)
        }

    private const val HEX = 16
    private const val ASCII_END = 0x7F
    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D
    private val JOINERS = setOf(ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER)

    private val ANGLES = mapOf('\uFF1C' to '<', '\uFF1E' to '>', '\uFE64' to '<', '\uFE65' to '>')
}

/** [this] with every unpaired UTF-16 surrogate replaced by U+FFFD. */
internal fun String.withoutLoneSurrogates(): String {
    if (none { it.isSurrogate() }) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                out.append(c).append(this[i + 1])
                i++
            }
            c.isSurrogate() -> out.append(REPLACEMENT)
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

private const val REPLACEMENT = '\uFFFD'
