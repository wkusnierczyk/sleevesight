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
 *   alone between two characters that are neither ASCII nor space nor format characters (an emoji
 *   sequence, Persian's non-joiner, a conjunct in an Indic script). A joiner beside ASCII, or in a run,
 *   is dropped. Which characters are format characters is the app's own table ([FORMATS]), not the
 *   platform's Unicode data: an older Android (the app runs from Android 8) knows fewer of them, so
 *   the phone drops what the JVM drops.
 *
 * No regex: a scan by code point, the same on the JVM and on Android.
 */
object SourceText {
    private val STEPS: List<(String) -> String> =
        listOf({ it.withoutLoneSurrogates() }, ::withPlainAngles, ::withoutInvisible)

    /** [text] through each step of the cleaning, in order. */
    fun clean(text: String): String = STEPS.fold(text) { acc, step -> step(acc) }

    /** [text] without its format characters, the joiners kept where writing needs them ([SourceText]). */
    internal fun withoutInvisible(text: String): String {
        if (text.none { it.isSurrogate() || isFormat(it.code) }) return text
        val out = StringBuilder(text.length)
        var i = 0
        var previous = -1
        while (i < text.length) {
            val point = text.codePointAt(i)
            val next = i + Character.charCount(point)
            val following = if (next < text.length) text.codePointAt(next) else -1
            if (kept(point, previous, following)) out.appendCodePoint(point)
            previous = point
            i = next
        }
        return out.toString()
    }

    /** [text] with the fullwidth and small angle signs as `<` and `>`. */
    internal fun withPlainAngles(text: String): String {
        if (text.none { it in ANGLES }) return text
        return buildString(text.length) { text.forEach { append(ANGLES[it] ?: it) } }
    }

    // Whether [point], between [previous] and [following] (-1 at an end), stays: not a format
    // character, or a joiner that joins.
    private fun kept(
        point: Int,
        previous: Int,
        following: Int,
    ): Boolean = !isFormat(point) || (point in JOINERS && joins(previous) && joins(following))

    private fun isFormat(point: Int): Boolean = point >= FIRST_FORMAT && FORMATS.any { point in it }

    /**
     * Every format character (category `Cf`) of Unicode 15.1, by code point: the table the app reads
     * instead of the platform's Unicode data, which on an older Android leaves some of them
     * unassigned (U+110CD, U+13430 to U+1343F). A test checks it against the JVM's data, so a newer
     * Unicode's additions are listed here as the build's JDK learns them.
     */
    internal val FORMATS: List<IntRange> =
        (
            "00AD 0600-0605 061C 06DD 070F 0890-0891 08E2 180E 200B-200F 202A-202E 2060-2064 2066-206F " +
                "FEFF FFF9-FFFB 110BD 110CD 13430-1343F 1BCA0-1BCA3 1D173-1D17A E0001 E0020-E007F"
        ).split(' ').map { range ->
            val (first, last) = range.split('-').let { it.first() to it.last() }
            first.toInt(HEX)..last.toInt(HEX)
        }

    private val FIRST_FORMAT = FORMATS.first().first
    private const val HEX = 16

    // Whether a joiner beside [point] may join: a character there, not ASCII, not space, not a format character.
    private fun joins(point: Int): Boolean =
        point > ASCII_END && !isFormat(point) && !Character.isWhitespace(point) && !Character.isSpaceChar(point)

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
