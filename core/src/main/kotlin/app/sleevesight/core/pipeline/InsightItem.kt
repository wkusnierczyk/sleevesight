package app.sleevesight.core.pipeline

import app.sleevesight.core.synthesis.CardKind

/**
 * The insights under *Key insights* on Home (#522), each its own [kind] of card: the song's
 * ([SONG]), the composer's ([COMPOSER], #1199), the artist's ([ARTIST]) and the album's ([ALBUM]),
 * a row each ([entries], in the rows' order); and one artist's by name ([named]: a performer's chip
 * under the Artist panel, an author's under the Composer panel, #1199), opened in place, never a row
 * of its own.
 *
 * The composer's, the artist's and a named artist's are all artists' cards ([CardKind.ARTIST]), each
 * about the artist [ItemInsights.name] gives: so the insight about an artist is written once and
 * shown wherever that artist is the subject, whether as the composer, the lead performer or another
 * performer (#240). Two items are the same when their [name]s are.
 *
 * @property name the item's name, as a saved state and a page keep it: `SONG`, `COMPOSER`, `ARTIST`,
 *   `ALBUM`, or [NAMED] and the artist's name.
 * @property artist the artist a [named] item is about; null for the others.
 */
class InsightItem private constructor(
    val kind: CardKind,
    val name: String,
    val artist: String? = null,
) {
    /**
     * The item's key in test tags: its kind's ([CardKind.key]: `song`, `artist`, `album`), but
     * `composer` for the composer's and `artist:<name>` for a named artist's.
     */
    val key: String
        get() =
            when {
                this == COMPOSER -> "composer"
                artist != null -> "${kind.key}:$artist"
                else -> kind.key
            }

    /** Whether it is about an artist: the composer's, the artist's or a named artist's. */
    val aboutArtist: Boolean get() = kind == CardKind.ARTIST

    override fun equals(other: Any?): Boolean = other is InsightItem && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        /** The song's insight. */
        val SONG: InsightItem = InsightItem(CardKind.SONG, "SONG")

        /** The composer's insight (#1199): about the composer, on a track whose roles tell one apart. */
        val COMPOSER: InsightItem = InsightItem(CardKind.ARTIST, "COMPOSER")

        /**
         * The artist's insight: about the lead performer once the roles tell a composer apart, else the
         * primary artist.
         */
        val ARTIST: InsightItem = InsightItem(CardKind.ARTIST, "ARTIST")

        /** The album's insight. */
        val ALBUM: InsightItem = InsightItem(CardKind.ALBUM, "ALBUM")

        /** The items that have a row, in the rows' order. */
        val entries: List<InsightItem> = listOf(SONG, COMPOSER, ARTIST, ALBUM)

        /** What a [named] item's [name] starts with, before the artist's name. */
        const val NAMED: String = "ARTIST:"

        /** The insight about [artist] by name (#1199): a performer's or an author's, opened in place. */
        fun named(artist: String): InsightItem = InsightItem(CardKind.ARTIST, NAMED + artist, artist)

        /** The item named [name] ([InsightItem.name]); null for none. */
        fun of(name: String): InsightItem? =
            entries.firstOrNull { it.name == name }
                ?: name.removePrefix(NAMED).takeIf { name.startsWith(NAMED) && it.isNotBlank() }?.let(::named)
    }
}
