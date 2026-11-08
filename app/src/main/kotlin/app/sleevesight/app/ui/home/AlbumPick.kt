package app.sleevesight.app.ui.home

import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.sources.ReleaseDate

/**
 * The album the player's name for it names, by its release groups, for the back of Home's cover
 * (#984): its group, with the others its name names; or several and none picked.
 */
internal sealed interface AlbumPick {
    /**
     * The album's release [group], and the [others] its name names besides (empty when it names one,
     * or [CardEntity.Album.of] tells the one album among them), for the lookup to take the one of them
     * the playing recording is on when [group] does not hold it (`AlbumTracklists.of`).
     */
    data class Groups(
        val group: String,
        val others: List<String> = emptyList(),
    ) : AlbumPick

    /** Several groups, none of them told apart by the player's year or by the earliest date. */
    data object Ambiguous : AlbumPick

    companion object {
        /**
         * The album [album] (the player's name for it) of a track by [primary], of the track's releases
         * [dates], as [key] reads names: as the album's insight keys it ([CardEntity.Album.of]), the one
         * group of them the name names (of several, the one album among them). When it names several
         * and no one album among them (#1062: three compilations named *Greatest Hits*, two singles
         * named *Tom's Diner*), one of them rather than none, of the albums among them when there are
         * any, else of all: of the groups with a release named in [playerYear], when it is given and
         * any has; of those, the one with the earliest named release, an undated one after every dated
         * one. The others go with it, so the lookup takes the one the playing recording is on.
         * [Ambiguous] when two share that earliest date (or are both undated); null when it names none.
         */
        fun of(
            album: String,
            primary: String,
            dates: List<ReleaseDate>,
            playerYear: String?,
            key: (String) -> String,
        ): AlbumPick? {
            val one = CardEntity.Album.of(album, primary, dates, key).group
            val named = ReleaseDate.named(album, dates, key).groupBy { it.group }
            return when {
                one != null -> Groups(one)
                named.isEmpty() -> null
                else -> among(named, playerYear)
            }
        }

        // One of the several groups [named] (each with its named releases), as [of] picks it.
        private fun among(
            named: Map<String, List<ReleaseDate>>,
            playerYear: String?,
        ): AlbumPick {
            val albums = named.filterValues { releases -> releases.any { it.type == ALBUM_TYPE } }.ifEmpty { named }
            val ofYear =
                playerYear?.let { year ->
                    albums.filterValues { releases -> releases.any { it.date?.let(ReleaseDate::yearIn) == year } }
                }
            val pool = ofYear?.ifEmpty { null } ?: albums

            // A group's earliest named release, an undated group after every dated one.
            fun earliest(group: String): String = named.getValue(group).mapNotNull { it.date }.minOrNull() ?: UNDATED
            val first = pool.keys.minOf(::earliest)
            val group = pool.keys.singleOrNull { earliest(it) == first }
            val others = named.keys.filter { it != group }.sortedBy(::earliest)
            return if (group == null) Ambiguous else Groups(group, others)
        }

        // MusicBrainz's primary type of an album's release group, as [CardEntity.Album.of] reads it.
        private const val ALBUM_TYPE = "Album"

        // Past every date, so an undated group sorts after the dated ones.
        private const val UNDATED = "￿"
    }
}
