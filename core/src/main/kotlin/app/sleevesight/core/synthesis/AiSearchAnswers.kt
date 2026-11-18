package app.sleevesight.core.synthesis

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.pages
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.text.SourceText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** What *Search with AI* found (#773), checked ([AiSearchAnswers]): never empty. */
sealed interface SearchAnswer {
    /**
     * *Dates*' answer: the album's release date ([album]), the recording's first release ([first]),
     * and a cover's original ([original]), each a partial ISO date as MusicBrainz gives one; at
     * least one of them.
     */
    data class Dates(
        val album: String? = null,
        val first: Released? = null,
        val original: Original? = null,
        val albumSource: String? = null,
    ) : SearchAnswer

    /**
     * *Genres*' answer: labels of the app's fixed list, in the answer's order, at most five; from the
     * web (#801), the pages their reasons cite ([sources]), in that order, each once. Each genre's
     * reason (#952), by its identity ([reasons]): why the track is that genre, checked and cited as
     * an explanation is ([Explanation]: its page `S1` from the web, `[AI]` from the model's own
     * knowledge); a genre the answer gives no usable reason for is not kept.
     */
    data class Genres(
        val genres: List<GenreLabel>,
        val sources: List<String> = emptyList(),
        val reasons: Map<String, Explanation> = emptyMap(),
    ) : SearchAnswer

    /** *Lyrics*' answer: one page per site, as *Lyrics* links MusicBrainz's (`lyricsLinks`). */
    data class Lyrics(
        val links: List<LyricsLink>,
    ) : SearchAnswer

    /** A genre's definition (#786), where Wikipedia has none: plain text, on one line. */
    data class Definition(
        val text: String,
    ) : SearchAnswer

    /**
     * What the song is about (#908), checked as an insight is ([GroundingCheck]): its sentences, each
     * with its citations and whether a page backs it ([grounded]), at least one backed; and the pages
     * its sentences cite among those it was given, in the order first cited ([pages]).
     */
    data class Explanation(
        val grounded: GroundedText,
        val pages: List<CitedPage>,
    ) : SearchAnswer

    /**
     * The spelling check's answer (#1293): the track's [title] as the page [source] spells it, and
     * its credited [artists] as it spells them, every one in the player's order, or none when they
     * are spelled as the player gives them.
     */
    data class Spelling(
        val title: String,
        val artists: List<String>,
        val source: String,
    ) : SearchAnswer
}

/**
 * A page an explanation (#908) was given, by the id the prompt gave it ([id], `S1`, `S2` …,
 * `ExplainPrompt.id`), its address ([url]) and its title, where it has one ([title]); kept with the
 * answer, so a kept explanation is read against the pages it was written from.
 */
@Serializable
data class CitedPage(
    val id: String,
    val url: String,
    val title: String? = null,
)

/**
 * A release dated [date], with its [title] and [type] where the answer gives them; from the web
 * (#801), the page that says so ([source]).
 */
data class Released(
    val date: String,
    val title: String? = null,
    val type: ReleaseType? = null,
    val source: String? = null,
)

/**
 * A cover's original recording: by [artist], recorded [date] (the answer's year, else the date of
 * the release it first came out on), and that release, where the answer gives its title.
 */
data class Original(
    val artist: String,
    val date: String,
    val release: Released? = null,
    val source: String? = null,
)

/** A release's type, as *Dates* names it: an album, a single or an EP. */
enum class ReleaseType {
    ALBUM,
    SINGLE,
    EP,
}

/** An answer as read ([AiSearchAnswers.read]). */
sealed interface SearchReading {
    /**
     * Not a JSON object, even with a code fence stripped, nor a one-key answer with a stray quote in
     * its text ([JsonReplies.oneTextIn]): the call failed.
     */
    data object Malformed : SearchReading

    /** A JSON object with nothing in it that passes the checks: the model found nothing. */
    data object NothingUsable : SearchReading

    /** Something usable, as checked. */
    data class Found(
        val answer: SearchAnswer,
    ) : SearchReading
}

/**
 * Reads and checks *Search with AI*'s answers (#773; `design/design.md`, *Search with AI*), pure:
 * the model answers from what it knows, so nothing it says is taken as it comes. A part that fails a
 * check is dropped, and the rest kept where it stands alone; a field of the wrong type is a field
 * left out.
 *
 * - **Dates** ([SearchKind.DATES]): partial ISO dates only (`YYYY`, `YYYY-MM`, `YYYY-MM-DD`) that are
 *   real dates (a 30 February is none), from 1877, the first recording, to today by [clock] (the
 *   phone's date, in UTC). A release's type only one of album, single or EP, else none. A first
 *   release later than the album's date is dropped (the recording is on the album, so it came out
 *   by then), and a cover's original later than either: of two that contradict each other, the one
 *   the other is checked against is kept. They are compared as precisely as both are known (`2014`
 *   and `2014-07` agree). A name (a release's title, an original's artist) on one line, its line
 *   breaks and runs of spaces made one space, and dropped when longer than [NAME_MAX]: a title that
 *   injects instructions, or a model that rambles, cannot fill the panel.
 * - **Genres** ([SearchKind.GENRES]): only names on the app's fixed list ([vocabulary]), matched as a
 *   source's tags are ([GenreVocabulary.labelsFor]: case and spacing aside, a synonym's spelling as
 *   one genre), once each, in the answer's order, at most five; each with its reason (#952), on one
 *   line, at most [REASON_MAX] characters, none with a link or a web address, checked as an
 *   insight's text is ([GroundingCheck]): from the model's knowledge, its sentences citing `[AI]`;
 *   from the web, its page ([GENRE_PAGE_ID], `S1`, the entry's `source`), the model's own knowledge
 *   not allowed. A sentence citing nothing, or what it may not, is kept, shown dimmed; a genre with
 *   no reason, one of citations or marks alone, or none of whose sentences is backed, is dropped,
 *   its chip never shown; none left,
 *   nothing usable.
 * - **Lyrics** ([SearchKind.LYRICS]): only `https` links whose host is Genius or a site of
 *   MusicBrainz's lyrics whitelist, or a subdomain of one, look-alikes rejected; one per site, the
 *   one whose path names the track's artist where there are several, as `lyricsLinks` chooses
 *   MusicBrainz's ([pages], with [accentFolder]). No request is made to a link: the app links to
 *   lyrics, it never fetches them (#728).
 * - **A genre's definition** ([SearchKind.DEFINITION], #786): plain text, on one line (line breaks
 *   and runs of spaces made one space), at most [DEFINITION_MAX] characters, and none with a link or
 *   a web address, or markup (Markdown's or HTML's marks, [MARKUP]): it stands in for Wikipedia's
 *   lead, shown as written, so a reply that rambles, links or formats is none.
 *
 * - **What the song is about** ([SearchKind.EXPLAIN], #908): only from the web, its text checked as
 *   an insight's is ([GroundingCheck], the model's own knowledge not allowed) against the ids of the
 *   pages it was given (`sources` given to [read]): a sentence citing nothing, or an id not given, is
 *   kept, shown dimmed, as an insight shows one, and an id not given is no page; with no sentence a
 *   page backs, nothing usable. On
 *   one line, at most [EXPLANATION_MAX] characters, and none with a link or a web address.
 *
 * **From the web** (#801, `pages` given to [read]): every value names the page it came from, one of
 * the pages the searches found ([sameAddress]: its fragment and a closing `/` aside); a value with
 * none, or another, is dropped, as a value that fails a check is. A date's page is its own
 * (`album_date.source`, `first_release.source`, `original.source`); a genre's is its own too
 * (`genres[].source`, #952), and a genre with none found is not kept; a lyrics link is a page
 * itself, so it must be one found.
 */
class AiSearchAnswers(
    private val vocabulary: GenreVocabulary,
    internal val accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
) {
    /**
     * The names of the app's fixed genre list, which a search of [SearchKind.GENRES] chooses from:
     * one per genre ([GenreVocabulary.identity]), its first tag, since the reply is read through the
     * vocabulary, which folds the other spellings anyway (#1011: `rock n roll`, `synthpop`).
     */
    val genreNames: Collection<String> get() = vocabulary.tags.distinctBy { vocabulary.identity(it) }

    /**
     * The answer to [kind] in [reply], checked; [artists] are the track's credited artists, which
     * choose between two lyrics pages on one site. With [pages] (#801), the addresses of the pages
     * the web searches found, the answer is one from the web, each value with its page among them.
     * An explanation ([SearchKind.EXPLAIN], #908) is read against the pages its prompt numbered
     * ([sources]); none, nothing usable. Its [artists]' names are no web address ("will.i.am").
     */
    fun read(
        kind: SearchKind,
        reply: String,
        artists: List<String> = emptyList(),
        pages: Collection<String>? = null,
        sources: List<CitedPage>? = null,
    ): SearchReading {
        val answer = objectIn(reply) ?: JsonReplies.oneTextIn(reply, kind) ?: return SearchReading.Malformed
        val found =
            if (kind == SearchKind.EXPLAIN) {
                explanationIn(answer, sources.orEmpty(), artists)
            } else if (pages != null) {
                fromWeb(kind, answer, artists, pages)
            } else {
                when (kind) {
                    SearchKind.DATES -> dates(answer)
                    SearchKind.GENRES -> genres(answer, artists, page = null)
                    SearchKind.LYRICS -> lyrics(answer, artists)
                    SearchKind.DEFINITION -> definition(answer)
                    SearchKind.EXPLAIN, SearchKind.SPELLING -> null
                }
            }
        return found?.let(SearchReading::Found) ?: SearchReading.NothingUsable
    }

    // [answer] from the web, each value with one of [pages] as its page; null for nothing.
    private fun fromWeb(
        kind: SearchKind,
        answer: JsonObject,
        artists: List<String>,
        pages: Collection<String>,
    ): SearchAnswer? {
        val found = { address: String? -> address?.let { a -> pages.firstOrNull { sameAddress(it, a) } } }
        return when (kind) {
            SearchKind.DATES -> webDates(answer, found)
            SearchKind.GENRES -> genres(answer, artists) { entry -> found(entry.text("source")) }
            SearchKind.LYRICS ->
                pages(answer.strings("lyrics_urls").mapNotNull(found), artists, accentFolder)
                    .takeIf { it.isNotEmpty() }
                    ?.let(SearchAnswer::Lyrics)
            SearchKind.DEFINITION -> definition(answer)
            SearchKind.EXPLAIN -> null
            SearchKind.SPELLING -> spellingIn(answer, artists, found)
        }
    }

    // The dates from the web that pass, each with its page ([found]); null for none.
    private fun webDates(
        answer: JsonObject,
        found: (String?) -> String?,
    ): SearchAnswer.Dates? {
        val today = today()
        val albumPart = answer.obj("album_date")
        val albumSource = found(albumPart?.text("source"))
        val album = date(albumPart?.text("date"), today)?.takeIf { albumSource != null }
        val firstPart = answer.obj("first_release")
        val first =
            found(firstPart?.text("source"))
                ?.let { source ->
                    released(firstPart, today)?.copy(source = source)
                }?.takeUnless { album != null && later(it.date, album) }
        val originalPart = answer.obj("original")
        val original =
            found(originalPart?.text("source"))
                ?.let { source ->
                    originalPart?.let { original(it, today) }?.copy(source = source)
                }?.takeUnless { kept -> listOfNotNull(album, first?.date).any { later(kept.date, it) } }
        return SearchAnswer
            .Dates(album, first, original, albumSource.takeIf { album != null })
            .takeIf { album != null || first != null || original != null }
    }

    // The dates that pass, or null for none.
    private fun dates(answer: JsonObject): SearchAnswer.Dates? {
        val today = today()
        val album = date(answer.text("album_date"), today)
        val first = released(answer.obj("first_release"), today)?.takeUnless { album != null && later(it.date, album) }
        val original =
            answer.obj("original")?.let { original(it, today) }?.takeUnless { found ->
                listOfNotNull(album, first?.date).any { later(found.date, it) }
            }
        return SearchAnswer.Dates(album, first, original).takeIf { album != null || first != null || original != null }
    }

    // A release in [part], when its date passes; its title and type where they do.
    private fun released(
        part: JsonObject?,
        today: String,
    ): Released? {
        val date = date(part?.text("date"), today) ?: return null
        return Released(date, part?.name("title"), typeOf(part?.text("type")))
    }

    // A cover's original in [part]: an artist, and a date, its year's or else its release's.
    private fun original(
        part: JsonObject,
        today: String,
    ): Original? {
        val artist = part.name("artist")
        val release = part.obj("release")
        val title = release?.name("title")
        val releaseDate = date(release?.text("date"), today)
        val date = date(part.text("year"), today) ?: releaseDate
        if (artist == null || date == null) return null
        val on = title?.let { Released(releaseDate ?: date, it, typeOf(release.text("type"))) }
        return Original(artist, date, on)
    }

    // The genres in [answer] (#952: `genres`, each `{"name", "reason"}`, from the web with its
    // `source`), each with its reason checked ([reasonIn]); [page] gives an entry's page found, null
    // for an answer from the model's knowledge. A name with no usable reason is left out before the
    // names are matched, so it takes no place among the five.
    private fun genres(
        answer: JsonObject,
        artists: List<String>,
        page: ((JsonObject) -> String?)?,
    ): SearchAnswer.Genres? {
        val reasoned =
            (answer["genres"] as? JsonArray).orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val name = entry.name("name") ?: return@mapNotNull null
                val reason = reasonIn(entry, artists, page) ?: return@mapNotNull null
                name to reason
            }
        val labels = vocabulary.labelsFor(reasoned.map { it.first }, level = GenreLevel.TRACK)
        // Each label's reason: the first entry whose name is that genre.
        val reasons =
            labels.associate { label ->
                label.identity to
                    reasoned
                        .first { (name, _) ->
                            vocabulary.labelsFor(listOf(name), level = GenreLevel.TRACK).firstOrNull()?.identity ==
                                label.identity
                        }.second
            }
        val sources = labels.flatMap { reasons.getValue(it.identity).pages }.map { it.url }.distinct()
        return labels.takeIf { it.isNotEmpty() }?.let { SearchAnswer.Genres(it, sources, reasons) }
    }

    private fun lyrics(
        answer: JsonObject,
        artists: List<String>,
    ): SearchAnswer.Lyrics? =
        pages(
            answer.strings("lyrics_urls"),
            artists,
            accentFolder,
        ).takeIf { it.isNotEmpty() }?.let(SearchAnswer::Lyrics)

    private fun definition(answer: JsonObject): SearchAnswer.Definition? =
        answer
            .text("definition")
            ?.let(::oneLine)
            ?.takeIf { it.length <= DEFINITION_MAX && !linksOrMarks(it) }
            ?.let(SearchAnswer::Definition)

    // Today by [clock], in UTC, as `YYYY-MM-DD`.
    private fun today(): String = isoDay(clock.now().toEpochMilliseconds().floorDiv(1.days.inWholeMilliseconds))

    companion object {
        /** The year of the first recording: no recording, and no release, is dated before it. */
        const val FIRST_YEAR: Int = 1877

        /** The longest name kept (a release's title, an original's artist), in characters. */
        const val NAME_MAX: Int = 200

        /**
         * The longest genre's definition kept (#786), in characters: three sentences of an
         * encyclopedia's lead, with room to spare.
         */
        const val DEFINITION_MAX: Int = 600

        /**
         * The longest explanation kept (#908), in characters: four short sentences and their
         * citations, with room to spare.
         */
        const val EXPLANATION_MAX: Int = 1_000

        /**
         * The longest reason for a genre kept (#952), in characters: one or two short sentences, with
         * their citations, and room to spare; one longer is a model that rambles.
         */
        const val REASON_MAX: Int = 500

        /** The id a genre's reason from the web cites its page by (#952): the entry's own `source`. */
        const val GENRE_PAGE_ID: String = "S1"

        /** The marks of Markdown or HTML a definition (#786) may not carry. */
        const val MARKUP: String = "<>[]`*#|"

        // A link or a web address: a scheme, `www.`, or a name under a common top-level domain.
        private val ADDRESS = Regex("""(?i)\b[a-z][a-z0-9+.-]*://|\bwww\.|\b[a-z0-9-]+\.(com|org|net|io|info)\b""")

        // Whether [text] has a link, a web address, or a mark of [MARKUP].
        private fun linksOrMarks(text: String): Boolean = text.any { it in MARKUP } || ADDRESS.containsMatchIn(text)

        private val DATE = Regex("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?")
        private const val MONTHS = 12

        /**
         * The JSON object in [reply], or null when there is none: its text trimmed, a Markdown code
         * fence around it stripped (with its language, ```` ```json ````), and, with words before or
         * after it, from its first `{` to its last `}`.
         */
        fun objectIn(reply: String): JsonObject? = JsonReplies.objectIn(reply)

        /**
         * [text] when it is a partial ISO date (`YYYY`, `YYYY-MM`, `YYYY-MM-DD`) of a real day, month
         * or year from [FIRST_YEAR] to [today] (`YYYY-MM-DD`), as precisely as both are known; else null.
         */
        fun date(
            text: String?,
            today: String,
        ): String? {
            val match = text?.let { DATE.matchEntire(it) } ?: return null
            val (yearText, monthText, dayText) = match.destructured
            val year = yearText.toInt()
            val month = monthText.toIntOrNull()
            val day = dayText.toIntOrNull()
            val real =
                year >= FIRST_YEAR &&
                    (month == null || month in 1..MONTHS) &&
                    (day == null || month != null && day in 1..daysIn(year, month))
            return text.takeIf { real && !later(it, today) }
        }

        /**
         * Whether partial ISO date [date] is later than [than], as precisely as both are known: of
         * `2015` and `2014-12-31`, yes; of `2014` and `2014-07-29`, no.
         */
        fun later(
            date: String,
            than: String,
        ): Boolean {
            val length = minOf(date.length, than.length)
            return date.take(length) > than.take(length)
        }

        /** The day [epochDay] days after 1970-01-01, as `YYYY-MM-DD` (the proleptic Gregorian calendar). */
        @Suppress("MagicNumber") // The civil-from-days algorithm's constants (H. Hinnant's `civil_from_days`).
        fun isoDay(epochDay: Long): String {
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val shifted = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * shifted + 2) / 5 + 1
            val month = if (shifted < 10) shifted + 3 else shifted - 9
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return year.toString().padStart(4, '0') + "-" + two(month) + "-" + two(day)
        }

        private fun two(value: Long): String = value.toString().padStart(2, '0')

        // The days of [month] in [year], in the Gregorian calendar.
        @Suppress("MagicNumber") // The calendar's own numbers.
        private fun daysIn(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }

        // A release's type, as the answer names it, in any case; null for any other word.
        private fun typeOf(text: String?): ReleaseType? =
            when (text?.lowercase()) {
                "album" -> ReleaseType.ALBUM
                "single" -> ReleaseType.SINGLE
                "ep" -> ReleaseType.EP
                else -> null
            }

        // The object at [key], when it is one.
        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

        // The strings of the array at [key], those that are strings; none when it is not an array.
        private fun JsonObject.strings(key: String): List<String> =
            (get(key) as? JsonArray).orEmpty().mapNotNull { element: JsonElement ->
                (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
    }
}

// The explanation in [answer] (#908), checked against the ids of [sources]: null for none, one too
// long or with an address, or one no page backs. The track's [artists] are masked, in any case,
// before the address test, so a name such as "will.i.am" is no address.
private fun explanationIn(
    answer: JsonObject,
    sources: List<CitedPage>,
    artists: List<String>,
): SearchAnswer.Explanation? {
    val text =
        answer
            .text("explanation")
            ?.let(::oneLine)
            ?.takeIf {
                it.length <= AiSearchAnswers.EXPLANATION_MAX &&
                    !EXPLANATION_ADDRESS.containsMatchIn(masked(it, artists))
            }
    val byId = sources.associateBy { it.id }
    val grounded =
        text?.let { (GroundingCheck.check(it, byId.keys, allowModel = false) as? InsightReply.Written)?.grounded }
    val sentences = grounded?.paragraphs?.flatten().orEmpty()
    val pages = sentences.flatMap { it.cites }.distinct().mapNotNull(byId::get)
    val backed = sentences.any { it.status == SentenceStatus.OK }
    return if (grounded == null || !backed) null else SearchAnswer.Explanation(grounded, pages)
}

// A genre's reason in [entry] (#952), checked as an explanation's text is ([explanationIn]): from the
// web ([page] not null), against its page, the entry's `source` found among the pages, which it cites
// as [AiSearchAnswers.GENRE_PAGE_ID], the model's own knowledge not allowed; from the model's
// knowledge, citing `[AI]`. Null for none, one too long or with an address, one whose page was not
// found, or one no sentence of which is backed.
private fun reasonIn(
    entry: JsonObject,
    artists: List<String>,
    page: ((JsonObject) -> String?)?,
): SearchAnswer.Explanation? {
    val text =
        entry
            .text("reason")
            ?.let(::oneLine)
            ?.takeIf {
                it.length <= AiSearchAnswers.REASON_MAX && !EXPLANATION_ADDRESS.containsMatchIn(masked(it, artists))
            }
            // Words, not citations alone ("[AI]", "[S1]") nor marks: a reason that says nothing is none.
            ?.takeIf { it.replace(CITATION_MARK, "").any(Char::isLetterOrDigit) }
    // From the web, its page, or none: no page found, no reason.
    val pages = page?.let { found -> listOfNotNull(found(entry)?.let { CitedPage(AiSearchAnswers.GENRE_PAGE_ID, it) }) }
    val grounded =
        text?.takeIf { page == null || pages?.isNotEmpty() == true }?.let {
            val ids = pages.orEmpty().map(CitedPage::id).toSet()
            (GroundingCheck.check(it, ids, allowModel = page == null) as? InsightReply.Written)?.grounded
        }
    val backed =
        grounded?.paragraphs?.flatten()?.any { it.status == SentenceStatus.OK || it.status == SentenceStatus.MODEL }
    return if (grounded != null && backed == true) SearchAnswer.Explanation(grounded, pages.orEmpty()) else null
}

// A citation in a genre's reason (#952), `[AI]` or `[S1]`, whatever it holds: removed, what is left
// must have words. The brackets escaped, inside the class too, for ICU.
private val CITATION_MARK = Regex("""\[[^\]]*\]""")

// [text] with each of [names] (not blank) put as a plain word, matched in any case.
private fun masked(
    text: String,
    names: List<String>,
): String =
    names.map(String::trim).filter(String::isNotEmpty).fold(text) { masked, name ->
        masked.replace(name, "name", ignoreCase = true)
    }

/**
 * A link or a web address in an explanation (#908): a scheme (`https://`), `www.`, or a bare domain,
 * any labels before a top-level domain of two or more lowercase letters (`songfacts.co.uk`,
 * `Genius.com`). Not a stop before a space (`Vol. 2`, `St. Louis`), initials or abbreviations whose
 * last part is one letter (`U.S.`, `e.g.`), a number (`3.5`), nor a capital after a stop with no space
 * (`St.Louis`). A genre's definition keeps its own check ([AiSearchAnswers], `ADDRESS`).
 */
private val EXPLANATION_ADDRESS: Regex =
    run {
        val label = """[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"""
        Regex("""(?i:\b[a-z][a-z0-9+.-]*://|\bwww\.)|\b$label(?:\.$label)*\.[a-z]{2,}\b""")
    }

// The string at [key], cleaned as text from sources is ([SourceText], #1041) and trimmed, when it is
// one and not blank once cleaned.
private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.let(SourceText::clean)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

// The name at [key] ([text]) on one line ([oneLine]); null when longer than
// [AiSearchAnswers.NAME_MAX].
private fun JsonObject.name(key: String): String? =
    text(key)?.let(::oneLine)?.takeIf { it.length <= AiSearchAnswers.NAME_MAX }

// [text] on one line: every control character and line or paragraph separator, and each run of
// spaces, one space.
private fun oneLine(text: String): String =
    text.map { if (InsightPrompt.breaksLine(it)) ' ' else it }.joinToString("").replace(SPACES, " ")

private val SPACES = Regex(" {2,}")

/**
 * Whether [page] and [address] name the same page (#801): the same text but for a fragment (`#…`), a
 * closing `/` and surrounding space, as a model may copy an address.
 */
fun sameAddress(
    page: String,
    address: String,
): Boolean {
    fun plain(text: String) = text.trim().substringBefore('#').trimEnd('/')
    return plain(page).isNotEmpty() && plain(page) == plain(address)
}

// The spelling from the web (#1293): its title on one line, without quotation marks around it, with its
// page ([found]); its artists only as many as the player's [artists], each on one line, else none.
// Null with no title or page.
private fun spellingIn(
    answer: JsonObject,
    artists: List<String>,
    found: (String?) -> String?,
): SearchAnswer.Spelling? {
    val source = found(answer.text("source"))
    val title = answer.name("title")?.trim { it in QUOTES || it.isWhitespace() }?.takeIf { it.isNotEmpty() }
    val named =
        (answer["artists"] as? JsonArray)
            .orEmpty()
            .mapNotNull {
                (it as? JsonPrimitive)
                    ?.takeIf { p -> p.isString }
                    ?.content
                    ?.let(::oneLine)
                    ?.trim()
            }.filter { it.isNotEmpty() && it.length <= AiSearchAnswers.NAME_MAX }
    return if (source == null || title == null) {
        null
    } else {
        SearchAnswer.Spelling(title, named.takeIf { it.size == artists.size }.orEmpty(), source)
    }
}

// The quotation marks a spelled title may come in, which are not part of it (#1293).
private const val QUOTES = "\"“”„«»'‘’"
