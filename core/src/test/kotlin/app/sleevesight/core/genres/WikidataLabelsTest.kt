package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wikidata's genres as labels (#1098): those the vocabulary holds, each weighing the same. */
class WikidataLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // The album whose release group is [GROUP] is [ALBUM], stating pop rock and blue-eyed soul.
    private class Wikidata(
        val failing: Boolean = false,
    ) : WikidataLookup {
        val asked = mutableListOf<String>()

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> {
            asked += "entities"
            return Lookup.Found(
                ids.associateWith {
                    WdEntity(
                        it,
                        "Private Eyes",
                        null,
                        mapOf(
                            Wd.GENRE to listOf("Q1", "Q2"),
                        ),
                    )
                },
            )
        }

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> {
            asked += "labels"
            return Lookup.Found(mapOf("Q1" to "pop rock", "Q2" to "blue-eyed soul").filterKeys { it in ids })
        }

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = error("not asked")

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> {
            asked += "$property=$value"
            return when {
                failing -> Lookup.Failed("io")
                property == Wd.MUSICBRAINZ_RELEASE_GROUP && value == GROUP -> Lookup.Found(listOf(ALBUM))
                else -> Lookup.Found(emptyList())
            }
        }
    }

    @Test
    fun `only the genres the vocabulary holds become labels, by their spelling or without "music"`() {
        val labels =
            WikidataLabels(vocabulary).labels(
                listOf(
                    StatedGenre("blue-eyed soul", GenreLevel.TRACK, null, SONG),
                    StatedGenre("rock music", GenreLevel.TRACK, null, SONG),
                    StatedGenre("alternative hip-hop", GenreLevel.TRACK, null, SONG),
                    StatedGenre("rhythm and blues", GenreLevel.TRACK, null, SONG),
                    StatedGenre("popular music", GenreLevel.TRACK, null, SONG),
                    StatedGenre("impressionist music", GenreLevel.TRACK, null, SONG),
                ),
            )
        assertEquals(listOf("Blue-Eyed Soul", "Rock", "Alternative Hip Hop", "R&B"), labels.map { it.label })
        assertTrue(labels.all { it.level == GenreLevel.TRACK && it.url == SONG && it.weight == WikidataLabels.WEIGHT })
    }

    @Test
    fun `an artist's genres are the artist's, linking the artist's item`() {
        val label =
            WikidataLabels(vocabulary)
                .labels(listOf(StatedGenre("soul music", GenreLevel.ARTIST, "Hall & Oates", ARTIST)))
                .single()
        assertEquals(GenreLabel("soul", "Soul", "soul", ARTIST, GenreLevel.ARTIST, "Hall & Oates", weight = 0.5), label)
    }

    @Test
    fun `an album's genres are its release group's item's, kept so the next track asks nothing`() =
        runTest {
            val wikidata = Wikidata()
            val labels = WikidataLabels(vocabulary, wikidata, artists = MemoryArtistResults())
            val found = labels.albumLookup(GROUP)
            assertFalse(found.failed)
            assertEquals(listOf("Pop Rock", "Blue-Eyed Soul"), found.labels.map { it.label })
            assertTrue(found.labels.all { it.level == GenreLevel.ALBUM && it.url == Wd.url(ALBUM) })
            val asked = wikidata.asked.size
            assertEquals(found, labels.albumLookup(GROUP))
            assertEquals(asked, wikidata.asked.size)
        }

    @Test
    fun `an album with no item has none, and a failed request fails and is asked again`() =
        runTest {
            assertEquals(LabelLookup(emptyList()), WikidataLabels(vocabulary, Wikidata()).albumLookup("other"))
            val failing = Wikidata(failing = true)
            val labels = WikidataLabels(vocabulary, failing)
            assertEquals(LabelLookup(emptyList(), failed = true), labels.albumLookup(GROUP))
            labels.albumLookup(GROUP)
            assertEquals(2, failing.asked.size)
        }

    @Test
    fun `a genre both a tag and Wikidata give shows once, credited to both, weighing their sum`() {
        val tags = vocabulary.labelsFor(listOf("pop", "soft rock", "new wave"), level = GenreLevel.ARTIST, artist = "A")
        val stated =
            WikidataLabels(vocabulary).labels(
                listOf(
                    StatedGenre("new wave", GenreLevel.ARTIST, "A", ARTIST),
                    StatedGenre("blue-eyed soul", GenreLevel.ARTIST, "A", ARTIST),
                ),
            )
        val row = combineLabels(mapOf(LabelSource.LASTFM to tags, LabelSource.WIKIDATA to stated))
        // Last.fm's by place, 1, 2/3 and 1/3; Wikidata's 1/2 each: New Wave 1/3 + 1/2 rises to second.
        assertEquals(listOf("Pop", "New Wave", "Soft Rock", "Blue-Eyed Soul"), row.map { it.genre.label })
        assertEquals(listOf(LabelSource.LASTFM, LabelSource.WIKIDATA), row[1].credits.map { it.source })
    }

    private companion object {
        const val GROUP = "rg-private-eyes"
        const val ALBUM = "Q3"
        const val SONG = "https://www.wikidata.org/wiki/Q1"
        const val ARTIST = "https://www.wikidata.org/wiki/Q2"
    }
}
