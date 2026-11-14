package app.sleevesight.core.resolution

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.core.text.DateWhen

/**
 * When a track's music was composed (#1132, [ComposedDate]), from what the sources' answers hold
 * already: no request of its own. MusicBrainz first ([ofWork]), Wikidata when MusicBrainz dates none
 * ([ofItem]); the order is Home's *Dates*' to keep.
 */
object ComposedDates {
    /**
     * When [work] was composed: the years its `composer` relationships date, from the earliest to the
     * latest of their begin and end dates (`1921-03` to `1924-03` for *Turandot*, 1921 to 1924). Of the
     * relationships naming one of [composers], the credited composers (`ArtistRoles.composers`, by
     * [names]' [NameMatcher.sameName]), when any does, since a work's composer relationship may name an
     * arranger or a sampled song's writer, whose dates are not this music's; else of them all. Null when
     * none of them is dated.
     */
    fun ofWork(
        work: MbWork,
        composers: List<String>,
        names: NameMatcher,
    ): ComposedDate? {
        val all = work.artistRelations.filter { it.type == COMPOSER_RELATION }
        val credited = all.filter { relation -> composers.any { names.sameName(relation.artistName, it) } }
        val years =
            credited
                .ifEmpty { all }
                .flatMap { listOfNotNull(it.begin, it.end) }
                .mapNotNull { DateWhen.of(it)?.year }
                .sorted()
        if (years.isEmpty()) return null
        return ComposedDate(years.first(), years.last(), SourceId.MUSICBRAINZ, "$WORK_PAGE${work.mbid}")
    }

    /**
     * When the song's Wikidata [item] says it was made: the year of its earliest inception (P571), a
     * date of the common era at a year's precision or finer; null when it has none. Not its first
     * publication (P577): K. 545, composed in 1788, was first published in 1805, and a song's
     * publication is the release *Dates* already says.
     */
    fun ofItem(item: WdEntity): ComposedDate? =
        item
            .values(Wd.INCEPTION)
            .filter { it.startsWith("+") }
            .mapNotNull { DateWhen.of(it.removePrefix("+").substringBefore('T'))?.year }
            .minOrNull()
            ?.let { year -> ComposedDate(year, year, SourceId.WIKIDATA, Wd.url(item.id)) }

    // A work's relationship naming who composed it.
    private const val COMPOSER_RELATION = "composer"

    // A work's page on MusicBrainz.
    private const val WORK_PAGE = "https://musicbrainz.org/work/"
}
