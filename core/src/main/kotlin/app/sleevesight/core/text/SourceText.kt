package app.sleevesight.core.text

/**
 * Text from players and sources as a person reading it sees it (#1041, from #217): what the app takes
 * from outside, before it reaches a prompt, *Sources*, a genre's quote or a report. One place, so every
 * way in is cleaned alike; each step is a function of the text, applied in [STEPS]' order, so a new one
 * goes in the list.
 *
 * - [withoutInvisible]: Unicode format characters (category `Cf`) are dropped: the bidirectional
 *   controls, the zero-width spaces, the word joiner, the byte-order mark, the tag block
 *   (U+E0000-E007F). A model reads them, a person sees nothing, so text could say to a model what a
 *   person checking it cannot find. The joiners U+200C and U+200D stay where writing needs them: one
 *   alone between two characters that are neither ASCII nor space nor format characters (an emoji
 *   sequence, Persian's non-joiner, a conjunct in an Indic script). A joiner beside ASCII, or in a run,
 *   is dropped.
 * - [withPlainAngles]: the fullwidth and small angle signs (`＜＞`, `﹤﹥`) become `<` and `>`, so a prompt
 *   escapes them as it does `<` and nothing looks like the tag that closes a fence; both halves of each
 *   pair, so a title in brackets still reads as one.
 *
 * No regex: a scan by code point, the same on the JVM and on Android.
 */
object SourceText {
    private val STEPS: List<(String) -> String> = listOf(::withoutInvisible, ::withPlainAngles)

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

    private fun isFormat(point: Int): Boolean = Character.getType(point) == Character.FORMAT.toInt()

    // Whether a joiner beside [point] may join: a character there, not ASCII, not space, not a format character.
    private fun joins(point: Int): Boolean =
        point > ASCII_END && !isFormat(point) && !Character.isWhitespace(point) && !Character.isSpaceChar(point)

    private const val ASCII_END = 0x7F
    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D
    private val JOINERS = setOf(ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER)

    private val ANGLES = mapOf('\uFF1C' to '<', '\uFF1E' to '>', '\uFE64' to '<', '\uFE65' to '>')
}
