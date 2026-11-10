package app.sleevesight.core.text

/**
 * The HTML character references in text a source sends as a page writes it (#1165): Exa's titles and
 * highlights carry `&#039;`, `&#8217;`, `&rsquo;` or `&amp;` where the page shows `'`, `’` or `&`.
 * Each becomes its character, as a browser shows it, once, where the text comes in; the cleaning
 * after it ([SourceText]) never decodes, so what decoding gives is never decoded again: `&amp;#039;`
 * is the text `&#039;`, as on the page.
 *
 * - By number: `&#` and ASCII digits, or `&#x` (or `&#X`) and ASCII hex digits, then `;`. Leading
 *   zeros are read however many; zero, another control (but the tab, the line feed, the form feed
 *   and the carriage return), DEL, a surrogate or a number past Unicode is U+FFFD; 128 to 159 are
 *   the Windows-1252 characters browsers read them as, so `&#146;s` is `’s`, and the five it leaves
 *   unassigned are U+FFFD.
 * - By name, then `;`: the markup's own (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`), Latin-1's
 *   (`&eacute;`, `&nbsp;` …) and HTML 4's punctuation and symbols (`&rsquo;`, `&ndash;`, `&hellip;`,
 *   `&euro;` …) ([NAMED]).
 * - Anything else stays as written: no `;` ("R&B", "&copy 1974"), a name not listed, a digit
 *   outside ASCII.
 *
 * One pass, no regex: each character is read once, apart from a name's few, the same on the JVM and
 * on Android.
 */
object HtmlReferences {
    /** [text] with its character references decoded; [text] itself when it has none. */
    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var decoded = false
        var i = 0
        while (i < text.length) {
            val reference = if (text[i] == '&') referenceAt(text, i) else null
            if (reference == null) {
                out.append(text[i])
                i++
            } else {
                out.appendCodePoint(reference.point)
                decoded = true
                i = reference.end
            }
        }
        return if (decoded) out.toString() else text
    }

    // The reference that starts at [start] of [text], an `&`; null when none does.
    private fun referenceAt(
        text: String,
        start: Int,
    ): Reference? = if (text.getOrNull(start + 1) == '#') numberAt(text, start + 2) else nameAt(text, start + 1)

    // The numbered reference whose digits start at [from], after its `#` (and its `x`, if hex). Its
    // value stops growing once past Unicode, so a number of any length reads as U+FFFD.
    private fun numberAt(
        text: String,
        from: Int,
    ): Reference? {
        val hex = text.getOrNull(from) == 'x' || text.getOrNull(from) == 'X'
        val radix = if (hex) HEX else DECIMAL
        val first = if (hex) from + 1 else from
        var i = first
        var value = 0
        while (i < text.length) {
            val digit = asciiDigit(text[i], radix)
            if (digit < 0) break
            if (value <= Character.MAX_CODE_POINT) value = value * radix + digit
            i++
        }
        return if (i > first && text.getOrNull(i) == ';') Reference(pointOf(value), i + 1) else null
    }

    // The named reference whose name starts at [from]: ASCII letters and digits, at most [MAX_NAME].
    private fun nameAt(
        text: String,
        from: Int,
    ): Reference? {
        var i = from
        while (i < text.length && i - from <= MAX_NAME && isAsciiLetterOrDigit(text[i])) i++
        return if (text.getOrNull(i) == ';') NAMED[text.substring(from, i)]?.let { Reference(it, i + 1) } else null
    }

    // The character a browser shows for [value]: U+FFFD for no character, a control (all but the tab,
    // the line feed, the form feed and the carriage return), or one of Windows-1252's five unassigned.
    private fun pointOf(value: Int): Int =
        when {
            value > Character.MAX_CODE_POINT || value in SURROGATES || value in REFUSED -> REPLACEMENT
            value in CONTROLS && value !in SPACES -> REPLACEMENT
            else -> WINDOWS_1252[value] ?: value
        }

    // [c]'s value as an ASCII digit in [radix] (10 or 16); -1 when it is none.
    private fun asciiDigit(
        c: Char,
        radix: Int,
    ): Int =
        when {
            c in '0'..'9' -> c - '0'
            radix == HEX && c in 'a'..'f' -> c - 'a' + DECIMAL
            radix == HEX && c in 'A'..'F' -> c - 'A' + DECIMAL
            else -> -1
        }

    private fun isAsciiLetterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** A reference's character, [point], and the index just after its `;`, [end]. */
    private class Reference(
        val point: Int,
        val end: Int,
    )

    /**
     * The named references decoded, by name: HTML 4's without its Greek letters and mathematical
     * signs: the markup's own, Latin-1's (U+00A0 to U+00FF, in order) and the punctuation and
     * symbols of its special set.
     */
    internal val NAMED: Map<String, Int> =
        pairs("quot:22 amp:26 apos:27 lt:3C gt:3E").toMap() +
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
            )

    /**
     * What a browser reads a reference to 128 to 159 as: Windows-1252's characters at those bytes
     * (the HTML standard's table); the five it leaves unassigned are in [REFUSED].
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

    // The longest name read: longer than any in [NAMED] (six), so a long word is not read whole.
    private const val MAX_NAME = 8

    private const val LATIN_1_START = 0xA0
    private const val DECIMAL = 10
    private const val HEX = 16
    private const val REPLACEMENT = 0xFFFD
    private val SURROGATES = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

    // The C0 controls, and those of them a reference may give: the tab, the line feed, the form feed
    // and the carriage return.
    private val CONTROLS = 0 until ' '.code
    private val SPACES = codes("09 0A 0C 0D")

    // Zero, DEL, and the five numbers Windows-1252 leaves unassigned.
    private val REFUSED = codes("00 7F 81 8D 8F 90 9D")

    // [spec]'s code points, in hex, separated by spaces.
    private fun codes(spec: String): Set<Int> = spec.split(' ').map { it.toInt(HEX) }.toSet()
}
