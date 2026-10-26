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
     * The genres (#558): the chips, and the panel of [genre], by its identity, the chip tapped on
     * Home, with the table, a row per level.
     */
    @Serializable
    data class Genres(
        val genre: String? = null,
    ) : HomePage
}
