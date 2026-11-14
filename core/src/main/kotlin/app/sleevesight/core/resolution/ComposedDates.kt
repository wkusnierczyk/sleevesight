package app.sleevesight.core.resolution

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.core.text.DateWhen

/**
 * When a track's music was composed (#1132, [ComposedDate]), from what the sources' answers hold
 * already: no request of its own. MusicBrainz first ([ofWork]), then the infobox of the song's
 * Wikipedia article ([ofInfobox], #1273), then the song's Wikidata item ([ofItem]); the order is Home's
 * *Dates*' to keep, and Wikipedia's adapter's between its two.
 */
object ComposedDates {
    /**
     * When [work] was composed: the years its `composer` relationships date, from the earliest to the
     * latest of their begin and end dates (`1921-03` to `1924-03` for *Turandot*, 1921 to 1924).
     *
     * With credited composers ([composers], `ArtistRoles.composers`), only the relationships naming one of
     * them, by MusicBrainz id ([ids], theirs as the resolution has them: "Peter Tchaikovsky" credited is
     * MusicBrainz's "Pyotr Ilyich Tchaikovsky") or by [names]' [NameMatcher.sameName], and none when none
     * does: a work's composer relationship may name an arranger or a sampled song's writer, whose dates are
     * not this music's, and with none of the composer's own, Wikidata may still date it ([ofItem]). With no
     * credited composer (a pop song, its writer singing it), all of them.
     *
     * Each person's dates make one span; several people's are one only where their spans overlap (two
     * co-writers of a song): where they do not (Bach's 1722 beside his arranger Busoni's 1897), they are
     * not one time of composing, and nothing is said, so Wikidata may answer. Null when none is dated.
     */
    fun ofWork(
        work: MbWork,
        composers: List<String>,
        names: NameMatcher,
        ids: Set<String> = emptySet(),
    ): ComposedDate? {
        val all = work.artistRelations.filter { it.type == COMPOSER_RELATION }
        val counted =
            if (composers.isEmpty()) {
                all
            } else {
                all.filter { relation ->
                    relation.artistMbid in ids || composers.any { names.sameName(relation.artistName, it) }
                }
            }
        val spans =
            counted
                .groupBy { it.artistMbid }
                .values
                .mapNotNull(::span)
                .sortedBy { it.first }
        // Folded from the earliest: each next span must begin by the end of those before it.
        val merged =
            spans.drop(1).fold(spans.firstOrNull()) { joined, next ->
                joined?.takeIf { next.first <= it.second }?.let { it.first to maxOf(it.second, next.second) }
            }
        return merged?.let { (from, to) -> ComposedDate(from, to, SourceId.MUSICBRAINZ, "$WORK_PAGE${work.mbid}") }
    }

    // One person's composer relationships' years, earliest to latest; null when none is dated.
    private fun span(relations: List<MbArtistRelation>): Pair<String, String>? {
        val years = relations.flatMap { listOfNotNull(it.begin, it.end) }.mapNotNull { DateWhen.of(it)?.year }.sorted()
        return years.firstOrNull()?.let { it to years.last() }
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

    /**
     * When the song's Wikipedia article says it was composed (#1273): the years its infobox gives in
     * `composed`, else `year` ([InfoboxDate]), from [wikitext], the article's opening section; [url]
     * the article's address. Null when its infobox dates none, or it has no infobox.
     *
     * Before the item's inception ([ofItem]) where both date it: of 20 well-known works sampled
     * (2026-11-14), the infobox gave a span where the item gave one year inside it (Beethoven's Fifth,
     * 1804 to 1808 beside 1807; *Suite bergamasque*, 1890 to 1905 beside 1905), and no other year
     * than the item's where both gave one.
     */
    fun ofInfobox(
        wikitext: String,
        url: String,
    ): ComposedDate? =
        InfoboxDate.years(wikitext)?.let { (from, to) ->
            ComposedDate(from.toString(), to.toString(), SourceId.WIKIPEDIA, url)
        }

    // A work's relationship naming who composed it.
    private const val COMPOSER_RELATION = "composer"

    // A work's page on MusicBrainz.
    private const val WORK_PAGE = "https://musicbrainz.org/work/"
}
