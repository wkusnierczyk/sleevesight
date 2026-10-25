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

    /** The genre labels' table, a row per level. */
    @Serializable
    data object Genres : HomePage

    /** Where a genre label comes from: the label kept by [key], its level, artist and genre. */
    @Serializable
    data class Genre(
        val key: String,
    ) : HomePage

    /** *Key insights*: the insight, with its actions. */
    @Serializable
    data object Insight : HomePage
}
