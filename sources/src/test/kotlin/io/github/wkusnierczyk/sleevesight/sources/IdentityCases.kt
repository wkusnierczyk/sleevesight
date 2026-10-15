// Playlist positions and Wikidata ids are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.sources

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Route

/**
 * The identity checks the Wikidata tests make, and the recording run records: one candidate item
 * per case, reached through MusicBrainz or found by name (the article's title, for the reader),
 * and the scope expected (null for a rejection).
 */
object IdentityCases {
    class Case(
        val track: Int,
        val item: String,
        val route: Route,
        val articleTitle: String?,
        val expected: Scope?,
    )

    val songs: List<Case> =
        listOf(
            Case(1, "Q7639816", Route.MUSICBRAINZ, null, Scope.SONG),
            Case(13, "Q1415482", Route.MUSICBRAINZ, null, Scope.SONG),
            Case(36, "Q12001651", Route.MUSICBRAINZ, null, Scope.SONG),
            // Chuck Berry's own song: the item names only later performers, and him as its writer (#76).
            Case(55, "Q1437885", Route.MUSICBRAINZ, null, Scope.SONG),
            Case(63, "Q797806", Route.MUSICBRAINZ, null, Scope.SONG),
            Case(12, "Q4091249", Route.NAME, "Reem", null),
            Case(37, "Q2014237", Route.NAME, "Ode to Newfoundland", Scope.SONG),
            Case(45, "Q5979792", Route.NAME, "I Will Follow You into the Dark", null),
            Case(48, "Q210401", Route.NAME, "Here Comes the Sun", null),
            Case(61, "Q104092599", Route.NAME, "Willow (song)", null),
            Case(76, "Q18552823", Route.NAME, "Interstellar (soundtrack)", null),
            Case(77, "Q293753", Route.NAME, "Forever Young", null),
        )
}
