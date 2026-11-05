package app.sleevesight.core.language

/**
 * A writing system, as far as the search in the track's language tells them apart (#902): by the
 * Unicode blocks of its letters ([of]), never by a platform's script data, which `:core` cannot use
 * (D2). Latin is no hint: a title in Latin letters may be in any language, or transliterated.
 *
 * [own] is the language the script names on its own, when it names one: Hangul is Korean, Tamil
 * script Tamil. Devanagari names Hindi, the most common of its languages, but a recorded language
 * written in it (Marathi, Nepali) wins over that default. A script several languages share (Han,
 * Cyrillic, Arabic) names none: it only confirms a recorded language written in it.
 */
@Suppress("MagicNumber") // Unicode's block boundaries, each a script's letters.
enum class Script(
    val own: String?,
    private val ranges: List<IntRange>,
) {
    LATIN(null, listOf(0x0041..0x005A, 0x0061..0x007A, 0x00C0..0x024F, 0x1E00..0x1EFF)),
    GREEK("el", listOf(0x0370..0x03FF, 0x1F00..0x1FFF)),
    CYRILLIC(null, listOf(0x0400..0x052F)),
    ARMENIAN("hy", listOf(0x0530..0x058F)),
    HEBREW("he", listOf(0x0590..0x05FF)),
    ARABIC(null, listOf(0x0600..0x06FF, 0x0750..0x077F, 0x08A0..0x08FF, 0xFB50..0xFDFF, 0xFE70..0xFEFF)),
    DEVANAGARI("hi", listOf(0x0900..0x097F, 0xA8E0..0xA8FF)),
    BENGALI("bn", listOf(0x0980..0x09FF)),
    GURMUKHI("pa", listOf(0x0A00..0x0A7F)),
    GUJARATI("gu", listOf(0x0A80..0x0AFF)),
    TAMIL("ta", listOf(0x0B80..0x0BFF)),
    TELUGU("te", listOf(0x0C00..0x0C7F)),
    KANNADA("kn", listOf(0x0C80..0x0CFF)),
    MALAYALAM("ml", listOf(0x0D00..0x0D7F)),
    THAI("th", listOf(0x0E00..0x0E7F)),
    GEORGIAN("ka", listOf(0x10A0..0x10FF, 0x1C90..0x1CBF)),
    HANGUL("ko", listOf(0x1100..0x11FF, 0x3130..0x318F, 0xA960..0xA97F, 0xAC00..0xD7AF)),
    KANA("ja", listOf(0x3040..0x30FF, 0x31F0..0x31FF, 0xFF66..0xFF9D)),
    HAN(null, listOf(0x3400..0x4DBF, 0x4E00..0x9FFF, 0xF900..0xFAFF, 0x20000..0x2FFFF)),
    ;

    /** Whether the code point [point] is in this script's blocks. */
    operator fun contains(point: Int): Boolean = ranges.any { point in it }

    /**
     * Whether this script writes words without spaces between them, so a name is found inside a
     * text as it is written, not as a whole word between spaces: Thai, kana and Han.
     */
    val unspaced: Boolean get() = this == THAI || this == KANA || this == HAN

    companion object {
        /**
         * The script [text] is written in, other than Latin, or null when it has no letter of
         * another script this enum knows. Kana makes it Japanese and Hangul Korean whatever Han
         * characters stand beside them (Japanese and Korean mix Han in); otherwise the script with
         * the most characters in [text].
         */
        fun of(text: String): Script? {
            val counts = mutableMapOf<Script, Int>()
            forEachCodePoint(text) { point ->
                entries.firstOrNull { it != LATIN && point in it }?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
            return when {
                KANA in counts -> KANA
                HANGUL in counts -> HANGUL
                else -> counts.maxByOrNull { it.value }?.key
            }
        }

        private fun forEachCodePoint(
            text: String,
            action: (Int) -> Unit,
        ) {
            var i = 0
            while (i < text.length) {
                val high = text[i]
                val low = text.getOrNull(i + 1)
                if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                    action(
                        SUPPLEMENTARY_BASE + ((high - Char.MIN_HIGH_SURROGATE) shl SURROGATE_BITS) +
                            (low - Char.MIN_LOW_SURROGATE),
                    )
                    i += 2
                } else {
                    action(high.code)
                    i++
                }
            }
        }

        private const val SUPPLEMENTARY_BASE = 0x10000
        private const val SURROGATE_BITS = 10
    }
}

/**
 * A language the search in the track's language knows (#902): its [code], the subdomain of its
 * Wikipedia ([wiki], `hi` for `hi.wikipedia.org`) and the language of Wikidata's labels in it
 * ([label]), both the code unless they differ; its English [name], for the prompt; the ISO 639-3
 * codes MusicBrainz records it by ([iso3]); the Wikidata items that stand for it ([items], the
 * values of P407 and P364); the [scripts] it is written in; and its word for a song ([song]), for
 * the web search in it.
 */
data class Language(
    val code: String,
    val name: String,
    val iso3: Set<String>,
    val items: Set<String>,
    val scripts: Set<Script>,
    val song: String,
    val wiki: String = code,
    val label: String = code,
) {
    /** The key of its Wikipedia's sitelink on Wikidata: `hiwiki`, `zh_yuewiki`. */
    val sitelink: String get() = wiki.replace('-', '_') + "wiki"

    /** Its Wikipedia's host: `hi.wikipedia.org`. */
    val host: String get() = "$wiki.wikipedia.org"
}

/**
 * The languages the search in the track's language knows (#902): English, which needs no search,
 * and the languages with a large Wikipedia that songs are often sung in, each with what names it.
 * A language not listed is not searched in: its code or item is not recognized as a hint. A
 * language joins here with its codes, items, scripts and word for a song.
 */
object Languages {
    val ENGLISH: Language = Language("en", "English", setOf("eng"), setOf("Q1860"), setOf(Script.LATIN), "song")

    /** Every language known, English first. */
    val ALL: List<Language> =
        listOf(
            ENGLISH,
            Language("hi", "Hindi", setOf("hin"), setOf("Q1568"), setOf(Script.DEVANAGARI), "गीत"),
            Language("mr", "Marathi", setOf("mar"), setOf("Q1571"), setOf(Script.DEVANAGARI), "गाणे"),
            Language("ne", "Nepali", setOf("nep", "npi"), setOf("Q33823"), setOf(Script.DEVANAGARI), "गीत"),
            Language("bn", "Bengali", setOf("ben"), setOf("Q9610"), setOf(Script.BENGALI), "গান"),
            Language("pa", "Punjabi", setOf("pan"), setOf("Q58635"), setOf(Script.GURMUKHI), "ਗੀਤ"),
            Language("gu", "Gujarati", setOf("guj"), setOf("Q5137"), setOf(Script.GUJARATI), "ગીત"),
            Language("ta", "Tamil", setOf("tam"), setOf("Q5885"), setOf(Script.TAMIL), "பாடல்"),
            Language("te", "Telugu", setOf("tel"), setOf("Q8097"), setOf(Script.TELUGU), "పాట"),
            Language("kn", "Kannada", setOf("kan"), setOf("Q33673"), setOf(Script.KANNADA), "ಹಾಡು"),
            Language("ml", "Malayalam", setOf("mal"), setOf("Q36236"), setOf(Script.MALAYALAM), "ഗാനം"),
            Language("ur", "Urdu", setOf("urd"), setOf("Q1617"), setOf(Script.ARABIC), "گانا"),
            Language("ko", "Korean", setOf("kor"), setOf("Q9176"), setOf(Script.HANGUL, Script.HAN), "노래"),
            Language("ja", "Japanese", setOf("jpn"), setOf("Q5287"), setOf(Script.KANA, Script.HAN), "曲"),
            Language(
                "zh",
                "Chinese",
                setOf("zho", "cmn"),
                setOf("Q7850", "Q9192", "Q727694"),
                setOf(Script.HAN),
                "歌曲",
            ),
            Language("yue", "Cantonese", setOf("yue"), setOf("Q9186"), setOf(Script.HAN), "歌曲", wiki = "zh-yue"),
            Language("th", "Thai", setOf("tha"), setOf("Q9217"), setOf(Script.THAI), "เพลง"),
            Language("el", "Greek", setOf("ell"), setOf("Q9129", "Q36510"), setOf(Script.GREEK), "τραγούδι"),
            Language("he", "Hebrew", setOf("heb"), setOf("Q9288"), setOf(Script.HEBREW), "שיר"),
            Language("ka", "Georgian", setOf("kat"), setOf("Q8108"), setOf(Script.GEORGIAN), "სიმღერა"),
            Language("hy", "Armenian", setOf("hye"), setOf("Q8785"), setOf(Script.ARMENIAN), "երգ"),
            Language("ru", "Russian", setOf("rus"), setOf("Q7737"), setOf(Script.CYRILLIC), "песня"),
            Language("uk", "Ukrainian", setOf("ukr"), setOf("Q8798"), setOf(Script.CYRILLIC), "пісня"),
            Language("bg", "Bulgarian", setOf("bul"), setOf("Q7918"), setOf(Script.CYRILLIC), "песен"),
            Language("sr", "Serbian", setOf("srp"), setOf("Q9299"), setOf(Script.CYRILLIC, Script.LATIN), "песма"),
            Language("ar", "Arabic", setOf("ara", "arb"), setOf("Q13955"), setOf(Script.ARABIC), "أغنية"),
            Language("fa", "Persian", setOf("fas", "pes"), setOf("Q9168"), setOf(Script.ARABIC), "آهنگ"),
            Language("tr", "Turkish", setOf("tur"), setOf("Q256"), setOf(Script.LATIN), "şarkı"),
            Language("pt", "Portuguese", setOf("por"), setOf("Q5146"), setOf(Script.LATIN), "canção"),
            Language("es", "Spanish", setOf("spa"), setOf("Q1321"), setOf(Script.LATIN), "canción"),
            Language("fr", "French", setOf("fra"), setOf("Q150"), setOf(Script.LATIN), "chanson"),
            Language("de", "German", setOf("deu"), setOf("Q188"), setOf(Script.LATIN), "Lied"),
            Language("it", "Italian", setOf("ita"), setOf("Q652"), setOf(Script.LATIN), "canzone"),
            Language("pl", "Polish", setOf("pol"), setOf("Q809"), setOf(Script.LATIN), "piosenka"),
            Language("nl", "Dutch", setOf("nld"), setOf("Q7411"), setOf(Script.LATIN), "lied"),
            Language("sv", "Swedish", setOf("swe"), setOf("Q9027"), setOf(Script.LATIN), "låt"),
            Language("id", "Indonesian", setOf("ind"), setOf("Q9240"), setOf(Script.LATIN), "lagu"),
            Language("vi", "Vietnamese", setOf("vie"), setOf("Q9199"), setOf(Script.LATIN), "bài hát"),
        )

    private val byCode = ALL.associateBy { it.code }
    private val byIso3 = ALL.flatMap { language -> language.iso3.map { it to language } }.toMap()
    private val byItem = ALL.flatMap { language -> language.items.map { it to language } }.toMap()

    /** The language of [code] ([Language.code]), or null. */
    fun ofCode(code: String): Language? = byCode[code]

    /** The language MusicBrainz records as [code] (ISO 639-3), or null: `zxx`, `mul`, `und` are none. */
    fun ofIso3(code: String): Language? = byIso3[code.lowercase()]

    /** The language Wikidata's item [item] stands for (`Q1568`, Hindi), or null. */
    fun ofItem(item: String): Language? = byItem[item]
}
