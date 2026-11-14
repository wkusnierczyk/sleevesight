package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedWork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole work's items for a composed work's part MusicBrainz links to none (#1133,
 * [IdentityCheck.wholeItems], apart from [IdentityCheck.musicBrainzItems]): K. 545's Andante
 * (`9727cab0`) has no Wikidata item; its sonata (`a2ad223d`) has Q991701, as Wikidata answered
 * `haswbstatement:P435` for each (2026-11-14).
 */
class IdentityCheckWholeWorkTest {
    private class FakeWikidata(
        val byWork: Map<String, Lookup<List<String>>>,
    ) : WikidataLookup {
        val asked = mutableListOf<String>()

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> = Lookup.Found(emptyMap())

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> = Lookup.Found(emptyMap())

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> {
            asked += "$property=$value"
            return byWork[value] ?: Lookup.Found(emptyList())
        }
    }

    private val sonata = ResolvedWork(SONATA, "Sonata for Piano no. 16 in C major, K. 545 \"Sonata facile\"")
    private val andante = ResolvedWork(ANDANTE, "Sonata for Piano no. 16 …: II. Andante", whole = sonata)
    private val resolved = Resolution(outcome = Outcome.SongArticle, recordingMbid = "e0a505da", work = andante)

    private fun check(wikidata: FakeWikidata) = IdentityCheck(wikidata, NameMatcher(JvmAccentFolder))

    // What the check makes of [resolution]: the items MusicBrainz links, then the whole's.
    private suspend fun IdentityCheck.both(resolution: Resolution): Pair<Lookup<List<String>>, Lookup<List<String>>> {
        val linked = musicBrainzItems(resolution)
        return linked to wholeItems(resolution, linked)
    }

    @Test
    fun `a part with no item of its own has the whole work's item apart, its own lookup as before`() =
        runTest {
            val wikidata = FakeWikidata(mapOf(SONATA to Lookup.Found(listOf("Q991701"))))
            val (linked, whole) = check(wikidata).both(resolved)
            assertEquals(Lookup.Found(emptyList()), linked)
            assertEquals(Lookup.Found(listOf("Q991701")), whole)
            assertEquals(listOf("P435=$ANDANTE", "P435=$SONATA"), wikidata.asked)
        }

    @Test
    fun `a part with an item of its own is checked by it, and the whole is not asked`() =
        runTest {
            val wikidata =
                FakeWikidata(
                    mapOf(ANDANTE to Lookup.Found(listOf("Q1")), SONATA to Lookup.Found(listOf("Q991701"))),
                )
            val (linked, whole) = check(wikidata).both(resolved)
            assertEquals(Lookup.Found(listOf("Q1")), linked)
            assertEquals(Lookup.Found(emptyList()), whole)
            assertEquals(listOf("P435=$ANDANTE"), wikidata.asked)
        }

    @Test
    fun `a failed search for the part's item is a failure, and the whole is not asked`() =
        runTest {
            val failed = Lookup.Failed("Offline")
            val wikidata = FakeWikidata(mapOf(ANDANTE to failed, SONATA to Lookup.Found(listOf("Q991701"))))
            val (linked, whole) = check(wikidata).both(resolved)
            assertEquals(failed, linked)
            assertEquals(Lookup.Found(emptyList()), whole)
            assertEquals(listOf("P435=$ANDANTE"), wikidata.asked)
        }

    @Test
    fun `a work with no whole asks for its own item only, as before`() =
        runTest {
            val wikidata = FakeWikidata(mapOf(SONATA to Lookup.Found(listOf("Q991701"))))
            val (_, whole) = check(wikidata).both(resolved.copy(work = andante.copy(whole = null)))
            assertEquals(Lookup.Found(emptyList()), whole)
            assertEquals(listOf("P435=$ANDANTE"), wikidata.asked)
        }

    private companion object {
        const val ANDANTE = "9727cab0-f302-3b71-b937-5ccd416dd36e"
        const val SONATA = "a2ad223d-d73b-3775-9580-fd25b4d986e1"
    }
}
