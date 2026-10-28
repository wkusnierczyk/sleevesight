package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.IdentityCheck.Verdict
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * *Show genres* off (#444, #545): the identity check asks Wikidata for no genre's label, and the
 * accepted item's evidence names no genre; its record labels, awards and dates are as before.
 */
class IdentityCheckGenresTest {
    /** Wikidata with one artist item, recording each label request. */
    private class RecordingWikidata : WikidataLookup {
        val labelRequests = mutableListOf<List<String>>()

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(mapOf(ARTIST.id to ARTIST).filterKeys { it in ids })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> {
            labelRequests += ids
            return Lookup.Found(LABELS.filterKeys { it in ids })
        }

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(ARTIST.values(property))

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())
    }

    private suspend fun lines(genres: Boolean): Pair<List<String>, List<String>> {
        val wikidata = RecordingWikidata()
        val verdict = IdentityCheck(wikidata, NameMatcher(JvmAccentFolder), genres).artist(ARTIST.id, RESOLVED)
        assertIs<Verdict.Accepted>(verdict)
        val texts = WikidataEvidence.lines(verdict, Instant.parse("2026-10-26T00:00:00Z")).map { it.text }
        return wikidata.labelRequests.flatten() to texts
    }

    @Test
    fun `with genres off no genre label is asked for and no genre line is written`() =
        runTest {
            val (asked, texts) = lines(genres = false)
            assertTrue(GENRE !in asked, "asked for $asked")
            assertTrue(texts.none { "genre" in it }, "wrote $texts")
            assertEquals(listOf("The record label of “Band” is Label.", "“Band” was formed in 1970."), texts)
        }

    @Test
    fun `with genres on the genre is named as before`() =
        runTest {
            val (asked, texts) = lines(genres = true)
            assertTrue(GENRE in asked, "asked for $asked")
            assertEquals(
                listOf(
                    "The genre of “Band” is folk rock.",
                    "The record label of “Band” is Label.",
                    "“Band” was formed in 1970.",
                ),
                texts,
            )
        }

    private companion object {
        const val GENRE = "Q30"
        const val LABEL = "Q31"
        const val MBID = "mbid-band"
        val LABELS = mapOf(GENRE to "folk rock", LABEL to "Label")
        val ARTIST =
            WdEntity(
                "Q20",
                "Band",
                "Band",
                mapOf(
                    Wd.MUSICBRAINZ_ARTIST to listOf(MBID),
                    Wd.GENRE to listOf(GENRE),
                    Wd.RECORD_LABEL to listOf(LABEL),
                    Wd.INCEPTION to listOf("+1970-00-00T00:00:00Z"),
                ),
            )
        val RESOLVED = ResolvedArtist("Band", MBID)
    }
}
