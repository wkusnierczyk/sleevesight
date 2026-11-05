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
 *
 * With a script found, the first recorded language written in it; else the first recorded one that is
 * not English. English, or none found, is none: no search.
 */
object TrackLanguage {
    /**
     * The language of a track named [names] (its title first, then its artists), from the
     * [recorded] languages, each list asked for only when the ones before it have not decided; null
     * for English or none. A script that names its language alone, with no other written in it
     * (Hangul, Tamil), decides without asking any.
     */
    suspend fun find(
        names: List<String>,
        recorded: List<suspend () -> List<Language>>,
    ): Language? {
        val script = names.firstNotNullOfOrNull { Script.of(it) }
        val own = script?.own?.let(Languages::ofCode)
        val alone = own != null && Languages.ALL.none { it != own && script in it.scripts }
        // With a script, the first recorded language written in it; else the first not English.
        val fits: (Language) -> Boolean =
            if (script != null) {
                { language -> script in language.scripts }
            } else {
                { language -> language != Languages.ENGLISH }
            }
        var found: Language? = null
        val hints = recorded.iterator()
        while (!alone && found == null && hints.hasNext()) found = hints.next()().firstOrNull(fits)
        return if (alone) own else found ?: own
    }

    /**
     * The languages of MusicBrainz's ISO 639-3 [codes], in their order, once each; unknown ones and
     * `zxx`, `mul`, `und` left out.
     */
    fun ofIso3(codes: List<String>): List<Language> = codes.mapNotNull(Languages::ofIso3).distinct()

    /** The languages of Wikidata's language [items] (`Q1568`), in their order, once each; unknown ones left out. */
    fun ofItems(items: List<String>): List<Language> = items.mapNotNull(Languages::ofItem).distinct()
}
