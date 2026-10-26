package app.sleevesight.app.ui.home

import kotlinx.serialization.Serializable

/**
 * A page of Home's (#447): an item of the home screen opened on a page of its own, under *Layout*'s
 * *Separate pages*. Each shows the component Home mounts in place under *Expandable boxes*, from the
 * same pipeline state as Home, for the track playing. A destination of the app's navigation graph.
 */
@Serializable
sealed interface HomePage {
    /** *Sources*, at its first screenful; with [cite], a citation tapped, unfolded to that line, flashed. */
    @Serializable
    data class Sources(
        val cite: String? = null,
    ) : HomePage

    /**
     * The genres' page with no genre given: the chips, and the first chip's panel. Kept as it was
     * before [GenresAt] (#558): a back stack saved by an earlier version, with the genres' page on
     * it, is restored by its destination, whose id is the hash of this serial name.
     */
    @Serializable
    data object Genres : HomePage

    /**
     * The genres' page opened at a genre (#558), by its identity, [genre], the chip tapped on Home:
     * the chips, and that genre's panel, with the table, a row per level. A route of its own beside
     * [Genres], which stays as it was.
     */
    @Serializable
    data class GenresAt(
        val genre: String,
    ) : HomePage
}
