package app.sleevesight.sources.wikipedia

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.ResolvedWork
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole work's item (#1133, [IdentityCheck.wholeItems]) as a song's candidate: after the
 * articles found by name, so a movement's own article (a Bach Air's, its item with no MusicBrainz work
 * id) is taken before its suite's; a movement with neither (K. 545's Andante) takes the whole's.
 */
class WikipediaWholeWorkTest {
    // Two musical works by Bach, the movement's (Q_AIR) and its suite's (Q_SUITE, whose P435 is the
    // whole work's MusicBrainz id); Bach with no MusicBrainz id, matched by name.
    private class Wikidata : WikidataLookup {
        private fun work(id: String) =
            WdEntity(
                id,
                if (id == AIR) "Air" else "Orchestral Suite No. 3",
                null,
                mapOf(
                    Wd.INSTANCE_OF to listOf("Q105543609"),
                    Wd.COMPOSER to listOf(BACH),
                    Wd.INCEPTION to listOf("+1730-00-00T00:00:00Z"),
                ),
            )

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(ids.filter { it == AIR || it == SUITE }.associateWith(::work))

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(mapOf(BACH to "Johann Sebastian Bach").filterKeys { it in ids })

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(if (value == "suite") listOf(SUITE) else emptyList())
    }

    private val track = RecordedTracks.normalize("Air", "Johann Sebastian Bach, An Orchestra")

    private val resolution =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "mb-r",
            artists = listOf(ResolvedArtist("Johann Sebastian Bach", null), ResolvedArtist("An Orchestra", null)),
            work = ResolvedWork("air", "Suite No. 3: II. Air", whole = ResolvedWork("suite", "Suite No. 3")),
        )

    // The items whose Wikidata lines Wikipedia's result holds, when every request to Wikipedia is
    // answered with [pages].
    private suspend fun items(pages: String): List<String> {
        val adapter =
            WikipediaSource(
                WikipediaClient(ReplayNetClient.ok(pages)),
                IdentityCheck(Wikidata(), RecordedTracks.names),
                RecordedTracks.names,
            )
        return adapter
            .lookup(track, CompletableDeferred(resolution))
            .evidence
            .mapNotNull { line -> listOf(AIR, SUITE).firstOrNull { line.url == Wd.url(it) } }
            .distinct()
    }

    @Test
    fun `the movement's own article found by name comes before the whole work's item`() =
        runTest {
            val air = """{"query":{"pages":[{"title":"Air","pageprops":{"wikibase_item":"$AIR"}}]}}"""
            val found = items(air)
            assertTrue(found == listOf(AIR), "$found")
        }

    @Test
    fun `with no article of its own, the whole work's item is taken`() =
        runTest {
            val found = items("""{"query":{"pages":[]}}""")
            assertTrue(found == listOf(SUITE), "$found")
        }

    private companion object {
        const val AIR = "Q_AIR"
        const val SUITE = "Q_SUITE"
        const val BACH = "Q_BACH"
    }
}
