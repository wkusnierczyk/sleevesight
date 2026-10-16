package app.sleevesight.core.resolution

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import kotlin.time.Instant

/**
 * Wikidata's evidence lines (`WD`), templated from an item the identity check accepted (D1;
 * `design/architecture.md`, *Source adapters*): genre, record label, notable awards, and the date
 * of first publication or inception. Each line links the item, in the scope the check gave it, so
 * an original song's facts are never credited to a cover. Wikidata is CC0.
 *
 * The lines come in a fixed order for the same item: genres, labels, awards, date.
 */
object WikidataEvidence {
    /** The properties whose values are items the lines name, so their labels are fetched with the check. */
    val NAMED_PROPERTIES: List<String> = listOf(Wd.GENRE, Wd.RECORD_LABEL, Wd.AWARD_RECEIVED)

    /** At most this many genres, labels and awards: a famous song can hold dozens of each. */
    const val MAX_VALUES: Int = 3

    /** The lines for an accepted item, dated [retrievedAt]. */
    fun lines(
        accepted: IdentityCheck.Verdict.Accepted,
        retrievedAt: Instant,
    ): List<EvidenceDraft> {
        val item = accepted.item
        val name = "“${item.label ?: item.id}”"

        fun named(property: String) =
            item
                .values(property)
                .mapNotNull { accepted.labels[it] }
                .distinct()
                .take(MAX_VALUES)
        val genres = named(Wd.GENRE)
        val labels = named(Wd.RECORD_LABEL)
        val texts = mutableListOf<String>()
        when (genres.size) {
            0 -> Unit
            1 -> texts += "The genre of $name is ${genres.single()}."
            else -> texts += "The genres of $name include ${EvidenceText.list(genres)}."
        }
        when (labels.size) {
            0 -> Unit
            1 -> texts += "The record label of $name is ${labels.single()}."
            else -> texts += "The record labels of $name include ${EvidenceText.list(labels)}."
        }
        named(Wd.AWARD_RECEIVED).forEach { texts += "$name received the $it." }
        dateLine(item, name, accepted.scope)?.let { texts += it }
        return texts.map { EvidenceDraft(SourceId.WIKIDATA, accepted.scope, it, Wd.url(item.id), retrievedAt) }
    }

    // A song's first publication, else its inception; an artist's inception (a group's formation).
    private fun dateLine(
        item: WdEntity,
        name: String,
        scope: Scope,
    ): String? {
        val published = item.values(Wd.PUBLICATION_DATE).mapNotNull { date(it) }.minOrNull()
        val inception = item.values(Wd.INCEPTION).mapNotNull { date(it) }.minOrNull()
        return when {
            scope == Scope.ARTIST -> inception?.let { "$name was formed ${EvidenceText.date(it)}." }
            published != null -> "$name was first published ${EvidenceText.date(published)}."
            inception != null -> "$name was created ${EvidenceText.date(inception)}."
            else -> null
        }
    }

    // Wikidata's `+1974-03-25T00:00:00Z` as `1974-03-25`; a date before the common era is left out.
    private fun date(time: String): String? =
        time
            .takeIf { it.startsWith("+") }
            ?.removePrefix("+")
            ?.substringBefore('T')
            ?.split('-')
            ?.let { parts -> parts.takeWhile { it.toIntOrNull() != 0 }.joinToString("-") }
            ?.ifEmpty { null }
}
