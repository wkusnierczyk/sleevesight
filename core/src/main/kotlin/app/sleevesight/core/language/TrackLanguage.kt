package app.sleevesight.core.language

/**
 * The language a track is sung in, for the search in it (#902; `design/architecture.md`, *Search in
 * the track's language*): named only by what is written or recorded, never by the artist's country
 * and never by an AI guess. The hints, strongest first:
 *
 * 1. The title, or else an artist's name, written in a script other than Latin ([Script.of]). A
 *    script that names a language on its own ([Script.own]) gives it; Devanagari's Hindi only when no
 *    recorded language written in Devanagari (Marathi, Nepali) says otherwise. A script several
 *    languages share (Han, Cyrillic, Arabic) only confirms a recorded language written in it.
 * 2. The recorded languages, in [find]'s order: MusicBrainz's lyrics language for the work, Wikidata's
 *    language of the work (the song's item, and the film's or album's it comes from), and the
 *    language MusicBrainz records for the tracklists of the recording's releases.
 * 3. The lines the English sources found about the track itself, by their script (#987, [ofLines]):
 *    a Hindi caption in Devanagari that the English web search found, for a title the player gives
 *    in Latin letters. Weakest, as a page about a song may quote a line in another language.
 *
 * With a script found, the first recorded language written in it; else the first hint that records a
 * language settles it: its first language that is not English, or, when it records English only, no
 * search, whatever a weaker hint records (English lyrics on a film's soundtrack with a Hindi
 * tracklist are English). English, or none found, is none: no search.
 */
object TrackLanguage {
    /**
     * The language of a track named [names] (its title first, then its artists), from the
     * [recorded] languages, each list asked for only when the ones before it have not decided. A
     * script that names its language alone, with no other written in it (Hangul, Tamil), decides
     * without asking any. Without a script, a hint recording English only decides too:
     * [Languages.ENGLISH], no search, told apart from null, when nothing names a language at all;
     * with a script, the script outweighs it.
     */
    @Suppress("RedundantSuspendModifier") // It calls the hints, suspend lambdas, which the rule misses.
    suspend fun find(
        names: List<String>,
        recorded: List<suspend () -> List<Language>>,
    ): Language? {
        val script = scriptOf(names)
        val own = script?.own?.let(Languages::ofCode)
        val alone = own != null && Languages.ALL.none { it != own && script in it.scripts }
        var found: Language? = null
        var settled = alone
        val hints = recorded.iterator()
        while (!settled && hints.hasNext()) {
            val languages = hints.next()()
            // With a script, the first recorded language written in it; else the hint's first not
            // English, or none when it records English alone.
            found =
                if (script != null) {
                    languages.firstOrNull { script in it.scripts }
                } else {
                    languages.firstOrNull { it != Languages.ENGLISH }
                }
            settled = found != null || (script == null && languages.isNotEmpty())
        }
        // Settled with nothing found: a hint without a script recorded English alone.
        return if (alone) own else found ?: if (settled) Languages.ENGLISH else own
    }

    /**
     * The languages of MusicBrainz's ISO 639-3 [codes], in their order, once each; unknown ones and
     * `zxx`, `mul`, `und` left out.
     */
    fun ofIso3(codes: List<String>): List<Language> = codes.mapNotNull(Languages::ofIso3).distinct()

    /** The script a track named [names] (its title first, then its artists) is written in, other than Latin. */
    fun scriptOf(names: List<String>): Script? = names.firstNotNullOfOrNull { Script.of(it) }

    /**
     * The language most of a recording's releases record for their tracklists ([codes], ISO 639-3,
     * one per release), as one hint: the head of the count, ties in the order given, unknown codes
     * left out; none when no release records a known one. So an English song whose releases are
     * twelve English tracklists and one Spanish is English, not searched in Spanish: one pressing
     * abroad does not name the language the song is sung in. For a track whose names are in a
     * [script] other than Latin, the majority among the languages written in it, so a Marathi song
     * titled in Devanagari whose releases record English more often than Marathi is Marathi.
     */
    fun ofReleases(
        codes: List<String>,
        script: Script? = null,
    ): List<Language> {
        val languages = codes.mapNotNull(Languages::ofIso3).filter { script == null || script in it.scripts }
        return listOfNotNull(languages.distinct().maxByOrNull { language -> languages.count { it == language } })
    }

    /** The languages of Wikidata's language [items] (`Q1568`), in their order, once each; unknown ones left out. */
    fun ofItems(items: List<String>): List<Language> = items.mapNotNull(Languages::ofItem).distinct()

    /**
     * The language the [texts] of the lines about a track name by their script (#987,
     * [Languages.ofLine]), as one hint: the one more than half of the lines mostly in a script other
     * than Latin ([Script.mostly]) name; none otherwise. Lines mostly in Latin letters count for
     * nothing, so one Devanagari caption among English pages names Hindi; a line in a script several
     * languages share (Han, Cyrillic, Arabic) names none and counts against, so two Devanagari
     * captions and two Cyrillic lines name nothing.
     */
    fun ofLines(texts: List<String>): List<Language> {
        val scripted = texts.filter { Script.mostly(it) != null }
        val named = scripted.mapNotNull { Languages.ofLine(it, known = null) }
        val head = named.distinct().maxByOrNull { language -> named.count { it == language } }
        return listOfNotNull(head?.takeIf { language -> named.count { it == language } * 2 > scripted.size })
    }
}
