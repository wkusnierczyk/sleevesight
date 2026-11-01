package app.sleevesight.core.synthesis

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.pages
import app.sleevesight.core.normalizer.AccentFolder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    ) : SearchAnswer

    /** *Genres*' answer: labels of the app's fixed list, in the answer's order, at most five. */
    data class Genres(
        val genres: List<GenreLabel>,
    ) : SearchAnswer

    /** *Lyrics*' answer: one page per site, as *Lyrics* links MusicBrainz's (`lyricsLinks`). */
    data class Lyrics(
        val links: List<LyricsLink>,
    ) : SearchAnswer
}

/** A release dated [date], with its [title] and [type] where the answer gives them. */
data class Released(
    val date: String,
    val title: String? = null,
    val type: ReleaseType? = null,
)

/**
 * A cover's original recording: by [artist], recorded [date] (the answer's year, else the date of
 * the release it first came out on), and that release, where the answer gives its title.
 */
data class Original(
    val artist: String,
    val date: String,
    val release: Released? = null,
)

/** A release's type, as *Dates* names it: an album, a single or an EP. */
enum class ReleaseType {
    ALBUM,
    SINGLE,
    EP,
}

/** An answer as read ([AiSearchAnswers.read]). */
sealed interface SearchReading {
    /** Not a JSON object, even with a code fence stripped: the call failed. */
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
 *   and `2014-07` agree).
 * - **Genres** ([SearchKind.GENRES]): only names on the app's fixed list ([vocabulary]), matched as a
 *   source's tags are ([GenreVocabulary.labelsFor]: case and spacing aside, a synonym's spelling as
 *   one genre), once each, in the answer's order, at most five.
 * - **Lyrics** ([SearchKind.LYRICS]): only `https` links whose host is Genius or a site of
 *   MusicBrainz's lyrics whitelist, or a subdomain of one, look-alikes rejected; one per site, the
 *   one whose path names the track's artist where there are several, as `lyricsLinks` chooses
 *   MusicBrainz's ([pages], with [accentFolder]). No request is made to a link: the app links to
 *   lyrics, it never fetches them (#728).
 */
class AiSearchAnswers(
    private val vocabulary: GenreVocabulary,
    internal val accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
) {
    /** The names of the app's fixed genre list, which a search of [SearchKind.GENRES] chooses from. */
    val genreNames: Collection<String> get() = vocabulary.tags

    /**
     * The answer to [kind] in [reply], checked; [artists] are the track's credited artists, which
     * choose between two lyrics pages on one site.
     */
    fun read(
        kind: SearchKind,
        reply: String,
        artists: List<String> = emptyList(),
    ): SearchReading {
        val answer = objectIn(reply) ?: return SearchReading.Malformed
        val found =
            when (kind) {
                SearchKind.DATES -> dates(answer)
                SearchKind.GENRES -> genres(answer)
                SearchKind.LYRICS -> lyrics(answer, artists)
            }
        return found?.let(SearchReading::Found) ?: SearchReading.NothingUsable
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
        return Released(date, part?.text("title"), typeOf(part?.text("type")))
    }

    // A cover's original in [part]: an artist, and a date, its year's or else its release's.
    private fun original(
        part: JsonObject,
        today: String,
    ): Original? {
        val artist = part.text("artist")
        val release = part.obj("release")
        val title = release?.text("title")
        val releaseDate = date(release?.text("date"), today)
        val date = date(part.text("year"), today) ?: releaseDate
        if (artist == null || date == null) return null
        val on = title?.let { Released(releaseDate ?: date, it, typeOf(release.text("type"))) }
        return Original(artist, date, on)
    }

    private fun genres(answer: JsonObject): SearchAnswer.Genres? {
        val names = answer.strings("genres")
        return vocabulary
            .labelsFor(names, level = GenreLevel.TRACK)
            .takeIf { it.isNotEmpty() }
            ?.let(SearchAnswer::Genres)
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

    // Today by [clock], in UTC, as `YYYY-MM-DD`.
    private fun today(): String = isoDay(clock.now().toEpochMilliseconds().floorDiv(1.days.inWholeMilliseconds))

    companion object {
        /** The year of the first recording: no recording, and no release, is dated before it. */
        const val FIRST_YEAR: Int = 1877

        private val lenient = Json { ignoreUnknownKeys = true }
        private val DATE = Regex("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?")
        private const val FENCE = "```"
        private const val MONTHS = 12

        /**
         * The JSON object in [reply], or null when there is none: its text trimmed, a Markdown code
         * fence around it stripped (with its language, ```` ```json ````), and, with words before or
         * after it, from its first `{` to its last `}`.
         */
        fun objectIn(reply: String): JsonObject? {
            var text = reply.trim()
            if (text.startsWith(FENCE)) {
                text = text.substringAfter('\n', "").trimEnd().removeSuffix(FENCE)
            }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end < start) return null
            return try {
                lenient.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
            } catch (
                @Suppress("SwallowedException") e: SerializationException,
            ) {
                null
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                null
            }
        }

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

        // The string at [key], trimmed, when it is one and not blank.
        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

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
