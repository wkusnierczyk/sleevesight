package app.sleevesight.core.text

/**
 * Text from players and sources as a person reading it sees it (#1041, from #217): what the app takes
 * from outside, before it reaches a prompt, *Sources*, a genre's quote or a report. One place, so every
 * way in is cleaned alike; each step is a function of the text, applied in [STEPS]' order, so a new one
 * goes in the list.
 *
 * - [withoutLoneSurrogates]: a lone UTF-16 surrogate becomes U+FFFD first, so dropping a character
 *   between two cannot join them into a new character (a tag's).
 * - [withEntitiesDecoded] (#1165): an HTML character reference a page or a search engine left in its
 *   text (`&#039;`, `&#8217;`, `&rsquo;`, `&amp;`) becomes its character, as a browser shows it:
 *   by number (from 128 to 159 the Windows-1252 character a browser reads it as, so `&#146;s` is
 *   `’s`; zero, a surrogate or past Unicode U+FFFD), or by one of the names in [NAMED] (HTML 4's
 *   Latin-1, punctuation and symbol names), with its `;`. Before the next steps, so a reference to
 *   an invisible character (`&#8203;`) or an angle sign is cleaned as the character would be. Never
 *   twice: a reference whose character is an ASCII letter or digit, `#` or `;` stays as written, so
 *   no decoded character completes another reference; and `&amp;` (or `&#38;`) stays as written
 *   where its `&` would start another (`&amp;#039;` is the text `&#039;` on the page, and stays so).
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
 * The steps run again until nothing changes: dropping an invisible character can bring together a
 * reference split by it, which is then read as a person sees it, so cleaning again changes nothing.
 * Each round that changes something after the first makes the text shorter, so the rounds end.
 *
 * No regex: a scan by code point, the same on the JVM and on Android.
 */
@Suppress("TooManyFunctions") // One for each step and each rule of the scan, and the two helpers callers share.
object SourceText {
    private val STEPS: List<(String) -> String> =
        listOf({ it.withoutLoneSurrogates() }, ::withEntitiesDecoded, ::withPlainAngles, ::withoutInvisible)

    /** [text] through each step of the cleaning, in order, and again until nothing changes. */
    fun clean(text: String): String {
        var current = text
        while (true) {
            val next = STEPS.fold(current) { acc, step -> step(acc) }
            if (next == current) return next
            current = next
        }
    }

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

    /** [text] with its HTML character references decoded, each once ([SourceText]). */
    internal fun withEntitiesDecoded(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var decoded = false
        var i = 0
        while (i < text.length) {
            val reference = if (text[i] == '&') referenceAt(text, i) else null
            if (reference != null && decodes(reference, text)) {
                out.append(reference.character)
                decoded = true
                i = reference.end
            } else {
                out.append(text[i])
                i++
            }
        }
        return if (decoded) out.toString() else text
    }

    // A character reference at [start] of [text], an `&`: its character and where it ends, after its
    // `;`; null when none starts there.
    private fun referenceAt(
        text: String,
        start: Int,
    ): Reference? {
        // Its `;` is looked for only as far as a reference can be, so a long text of `&`s is read once.
        val window = text.substring(start + 1, minOf(text.length, start + MAX_REFERENCE + 2))
        val semicolon = window.indexOf(';')
        if (semicolon < 0) return null
        val body = window.substring(0, semicolon)
        return when {
            body.startsWith("#x") || body.startsWith("#X") -> numbered(body.drop(2), HEX)
            body.startsWith("#") -> numbered(body.drop(1), DECIMAL)
            else -> NAMED[body]
        }?.let { Reference(it, start + semicolon + 2) }
    }

    // The character of the number in [digits], in [radix]: as a browser reads it; null when not a number.
    private fun numbered(
        digits: String,
        radix: Int,
    ): String? {
        if (digits.isEmpty() || digits.any { Character.digit(it, radix) < 0 }) return null
        val point =
            digits
                .trimStart('0')
                .ifEmpty { "0" }
                .takeIf { it.length <= MAX_DIGITS }
                ?.toInt(radix)
                ?.takeUnless { it == 0 || it > Character.MAX_CODE_POINT || it in SURROGATES }
        return point?.let { String(Character.toChars(WINDOWS_1252[it] ?: it)) } ?: REPLACEMENT.toString()
    }

    // Whether [reference], in [text], is decoded: not to a character a reference is written with, nor
    // to an `&` that would start another with the visible text after it.
    private fun decodes(
        reference: Reference,
        text: String,
    ): Boolean {
        val character = reference.character.singleOrNull()
        return when {
            character == null -> true
            character == '&' -> referenceAt("&" + visibleAfter(text, reference.end), 0) == null
            else -> !(character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "#;")
        }
    }

    // The text after [from] in [text] without the characters the cleaning may drop, as long as a
    // reference can be.
    private fun visibleAfter(
        text: String,
        from: Int,
    ): String =
        buildString {
            var i = from
            while (i < text.length && length <= MAX_REFERENCE) {
                val point = text.codePointAt(i)
                if (!looked(point)) appendCodePoint(point)
                i += Character.charCount(point)
            }
        }

    /** A character reference's [character], and the index just after its `;` ([end]). */
    private class Reference(
        val character: String,
        val end: Int,
    )

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

    /**
     * The named character references decoded: HTML 4's, without its Greek letters and mathematical
     * signs: the markup's own, Latin-1's (U+00A0 to U+00FF, in order) and the punctuation and
     * symbols of its special set.
     */
    internal val NAMED: Map<String, String> =
        (
            pairs("quot:22 amp:26 apos:27 lt:3C gt:3E") +
                (
                    "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg " +
                        "plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 " +
                        "iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
                        "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash " +
                        "Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring " +
                        "aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve " +
                        "oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
                ).split(' ').mapIndexed { i, name -> name to LATIN_1_START + i } +
                pairs(
                    "OElig:152 oelig:153 Scaron:160 scaron:161 Yuml:178 fnof:192 circ:2C6 tilde:2DC " +
                        "ensp:2002 emsp:2003 thinsp:2009 zwnj:200C zwj:200D lrm:200E rlm:200F " +
                        "ndash:2013 mdash:2014 lsquo:2018 rsquo:2019 sbquo:201A ldquo:201C rdquo:201D " +
                        "bdquo:201E dagger:2020 Dagger:2021 bull:2022 hellip:2026 permil:2030 " +
                        "prime:2032 Prime:2033 lsaquo:2039 rsaquo:203A oline:203E frasl:2044 euro:20AC " +
                        "trade:2122",
                ).map { (name, point) -> name to point }
        ).associate { (name, point) -> name to String(Character.toChars(point)) }

    /**
     * The characters a browser reads a reference to 128 to 159 as: Windows-1252's at those bytes
     * (the HTML standard's table); the five it leaves unassigned stay as the number says.
     */
    private val WINDOWS_1252: Map<Int, Int> =
        pairs(
            "80:20AC 82:201A 83:192 84:201E 85:2026 86:2020 87:2021 88:2C6 89:2030 8A:160 8B:2039 " +
                "8C:152 8E:17D 91:2018 92:2019 93:201C 94:201D 95:2022 96:2013 97:2014 98:2DC 99:2122 " +
                "9A:161 9B:203A 9C:153 9E:17E 9F:178",
        ).associate { (from, to) -> from.toInt(HEX) to to }

    // [spec]'s pairs, `first:point` with the point in hex, separated by spaces.
    private fun pairs(spec: String): List<Pair<String, Int>> =
        spec.split(' ').map { it.substringBefore(':') to it.substringAfter(':').toInt(HEX) }

    private const val LATIN_1_START = 0xA0

    // The longest reference read (its `&` and `;` aside): longer than any name and any number.
    private const val MAX_REFERENCE = 32

    // The most digits of a number read, past its leading zeros: seven, enough for Unicode's last in
    // either base and few enough for an `Int`; a longer number is past Unicode.
    private const val MAX_DIGITS = 7

    private val SURROGATES = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

    private const val DECIMAL = 10
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
