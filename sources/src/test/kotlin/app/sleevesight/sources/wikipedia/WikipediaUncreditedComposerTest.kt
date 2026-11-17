package app.sleevesight.sources.wikipedia

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composer MusicBrainz names for a classical work whom the track does not credit (#1376,
 * `ArtistRoles.uncredited`): Wikipedia reaches their item by the id of the work's `composer`
 * relationship and checks it as a credited artist's, after the credited artists. *Nessun Dorma* by
 * Pavarotti alone, Puccini the work's composer.
 */
class WikipediaUncreditedComposerTest {
    // Pavarotti's item (Q-lp) and Puccini's (Q-gp), each found by its MusicBrainz id, with a genre each.
    private class Wikidata : WikidataLookup {
        val asked = mutableListOf<String>()

        private val claims =
            mapOf(
                "Q-lp" to mapOf(Wd.MUSICBRAINZ_ARTIST to listOf("mb-lp"), Wd.GENRE to listOf("Q-tenor")),
                "Q-gp" to mapOf(Wd.MUSICBRAINZ_ARTIST to listOf("mb-gp"), Wd.GENRE to listOf("Q-opera")),
            )

        private val names = mapOf("Q-lp" to "Luciano Pavarotti", "Q-gp" to "Giacomo Puccini")

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.associateWith { WdEntity(it, names[it] ?: it, null, claims[it].orEmpty()) })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(LABELS.filterKeys { it in ids })

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(claims[id]?.get(property).orEmpty())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> {
            if (property == Wd.MUSICBRAINZ_ARTIST) asked += value
            val found = claims.filterValues { property == Wd.MUSICBRAINZ_ARTIST && value in it[property].orEmpty() }
            return Lookup.Found(found.keys.toList())
        }
    }

    private val track = RecordedTracks.normalize("Nessun Dorma", "Luciano Pavarotti")

    private fun resolution(
        uncredited: List<String>,
        provisional: Boolean = false,
    ) = Resolution(
        outcome = Outcome.SongArticle,
        recordingMbid = "mb-r",
        artists = listOf(ResolvedArtist("Luciano Pavarotti", "mb-lp")),
        credits =
            listOf(
                Credit(CreditRole.COMPOSER, "Giacomo Puccini", "mb-gp"),
                Credit(CreditRole.LIBRETTIST, "Giuseppe Adami", "mb-ga"),
            ),
        roles =
            ArtistRoles(emptyList(), listOf("Luciano Pavarotti"), classical = true, uncredited = uncredited)
                .takeUnless { provisional },
        provisional = provisional,
    )

    private suspend fun lookup(
        wikidata: Wikidata,
        resolution: Resolution,
    ): SourceResult {
        val adapter =
            WikipediaSource(
                WikipediaClient(ReplayNetClient.ok("""{"query":{"pages":[]}}""")),
                IdentityCheck(wikidata, RecordedTracks.names, true),
                RecordedTracks.names,
            )
        return adapter.lookup(track, CompletableDeferred(resolution))
    }

    private suspend fun stated(
        wikidata: Wikidata,
        resolution: Resolution,
    ): List<StatedGenre> = lookup(wikidata, resolution).statedGenres

    @Test
    fun `the uncredited composer's item is reached by the work's id and stated as theirs, after the performer's`() =
        runTest {
            val wikidata = Wikidata()
            assertEquals(
                listOf(
                    StatedGenre("tenor", GenreLevel.ARTIST, "Luciano Pavarotti", Wd.url("Q-lp")),
                    StatedGenre("opera", GenreLevel.ARTIST, "Giacomo Puccini", Wd.url("Q-gp")),
                ),
                stated(wikidata, resolution(listOf("Giacomo Puccini"))),
            )
            // The composer only: the librettist is no composer.
            assertEquals(listOf("mb-lp", "mb-gp"), wikidata.asked)
        }

    @Test
    fun `with no uncredited composer, the credited artists' alone, as before`() =
        runTest {
            val wikidata = Wikidata()
            assertEquals(
                listOf(StatedGenre("tenor", GenreLevel.ARTIST, "Luciano Pavarotti", Wd.url("Q-lp"))),
                stated(wikidata, resolution(emptyList())),
            )
            assertEquals(listOf("mb-lp"), wikidata.asked)
        }

    @Test
    fun `a result made while the work's lookup failed is not kept, so the run that has the work finds the composer`() =
        runTest {
            // The work not looked up: no roles, no uncredited composer; the result shown, not kept.
            val failed = Wikidata()
            val first = lookup(failed, resolution(emptyList(), provisional = true))
            assertTrue(first.provisional)
            assertEquals(listOf("mb-lp"), failed.asked)
            // MusicBrainz answers on the next run: Puccini's article is looked up, and the result kept.
            val recovered = Wikidata()
            val again = lookup(recovered, resolution(listOf("Giacomo Puccini")))
            assertFalse(again.provisional)
            assertEquals(listOf("mb-lp", "mb-gp"), recovered.asked)
        }

    private companion object {
        val LABELS = mapOf("Q-tenor" to "tenor", "Q-opera" to "opera")
    }
}
