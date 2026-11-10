package app.sleevesight.sources.album

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.articleUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A classical album's evidence (#1130): its title is shared by other performers' recordings of the
 * same works, so what is found by its name must name one of its performers, not the composer the
 * track credits first alone.
 */
class ClassicalAlbumSourceTest {
    private val names = NameMatcher(JvmAccentFolder)

    // A classical album (#1130): Buniatishvili's, its title shared by Shelley's recording of the same
    // concertos, both on Wikipedia as `Mozart: Piano Concertos Nos. 20 & 23` by name.
    private val concertos = "Mozart: Piano Concertos Nos. 20 & 23"

    private val classical =
        AlbumQuery(
            concertos,
            "Wolfgang Amadeus Mozart",
            group = null,
            performers = listOf("Khatia Buniatishvili", "Academy of St. Martin in the Fields"),
        )

    // The two albums' items: Buniatishvili's names her and the Academy as its performers, not
    // Mozart; Shelley's names him and the London Mozart Players.
    private fun concertoItems(): Map<String, WdEntity> {
        val album = mapOf(Wd.INSTANCE_OF to listOf("Q482994"))
        return mapOf(
            "Q20" to
                WdEntity(
                    "Q20",
                    concertos,
                    "$concertos (Khatia Buniatishvili album)",
                    album + (Wd.PERFORMER to listOf("Q21", "Q22")),
                ),
            "Q30" to WdEntity("Q30", concertos, "$concertos (album)", album + (Wd.PERFORMER to listOf("Q31", "Q32"))),
        )
    }

    private val concertoLabels =
        mapOf(
            "Q21" to "Khatia Buniatishvili",
            "Q22" to "Academy of St Martin in the Fields",
            "Q31" to "Howard Shelley",
            "Q32" to "London Mozart Players",
            "Q40" to "Wolfgang Amadeus Mozart",
        )

    @Test
    fun `a classical album is found by its name as an album by a performer, not the composer`() =
        runTest {
            val leads =
                mapOf(
                    "$concertos (Khatia Buniatishvili album)" to
                        "$concertos is a 2024 album by Khatia Buniatishvili with the Academy.",
                )
            // Found by its plain name, as either recording could be.
            val wikipedia = Wikipedia(pages = mapOf(concertos to "Q20"), leads = leads)
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(concertoItems(), labels = concertoLabels),
                    WikipediaClient(wikipedia),
                    names,
                )
            // Before #1130 the item had to name the composer among its performers, and was dropped.
            val found = source.lookup(classical)
            assertEquals(
                listOf(SourceId.WIKIPEDIA to articleUrl("$concertos (Khatia Buniatishvili album)")),
                found.drafts.map { it.source to it.url },
            )
        }

    @Test
    fun `another performer's album of a classical album's name is not taken, nor one naming the composer alone`() =
        runTest {
            val leads = mapOf("$concertos (album)" to "$concertos is a 1991 album by Howard Shelley.")
            val shelley = Wikipedia(pages = mapOf("$concertos (album)" to "Q30"), leads = leads)
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(concertoItems(), labels = concertoLabels),
                    WikipediaClient(shelley),
                    names,
                )
            assertTrue(source.lookup(classical).drafts.isEmpty())
            // An item whose one performer is the composer may be anyone's recording of the works.
            val byComposer =
                WdEntity(
                    "Q30",
                    concertos,
                    "$concertos (album)",
                    mapOf(Wd.INSTANCE_OF to listOf("Q482994"), Wd.PERFORMER to listOf("Q40")),
                )
            val composerOnly =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(mapOf("Q30" to byComposer), labels = concertoLabels),
                    WikipediaClient(shelley),
                    names,
                )
            assertTrue(composerOnly.lookup(classical).drafts.isEmpty())
            // The same album without performers (no composer named in the title): by its artist, as before.
            assertEquals(
                listOf(SourceId.WIKIPEDIA),
                composerOnly.lookup(classical.copy(performers = emptyList())).drafts.map { it.source },
            )
        }

    @Test
    fun `a classical album's article is looked for under its first performer's name`() =
        runTest {
            val title = "$concertos (Khatia Buniatishvili album)"
            val wikipedia =
                Wikipedia(
                    pages = mapOf(title to "Q20"),
                    leads =
                        mapOf(
                            title to "$concertos is a 2024 album.",
                        ),
                )
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(concertoItems(), labels = concertoLabels),
                    WikipediaClient(wikipedia),
                    names,
                )
            assertEquals(listOf(articleUrl(title)), source.lookup(classical).drafts.map { it.url })
        }

    @Test
    fun `a classical album is searched once whatever performers, or order, each of its tracks credits`() =
        runTest {
            val web = Web()
            val webKept = MemoryArtistResults()
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(emptyMap()),
                    WikipediaClient(Wikipedia()),
                    names,
                    web = web,
                    webKept = webKept,
                )
            // By its release group: a track crediting the soloist alone finds the album's lines.
            val grouped = classical.copy(group = "rg-concertos")
            checkNotNull(source.searchWeb(grouped))
            val solo = grouped.copy(performers = listOf("Khatia Buniatishvili"))
            assertFalse(source.lookup(solo).webDue)
            checkNotNull(source.searchWeb(solo))
            assertEquals(1, web.asked.size)
            // By name: the performers' order and repeats do not matter.
            checkNotNull(source.searchWeb(classical))
            val reordered = classical.copy(performers = classical.performers.reversed() + classical.performers)
            assertFalse(source.lookup(reordered).webDue)
            checkNotNull(source.searchWeb(reordered))
            assertEquals(2, web.asked.size)
        }

    @Test
    fun `a classical album's web search is given its performers, and kept apart from another recording's`() =
        runTest {
            val web = Web()
            val webKept = MemoryArtistResults()
            val source =
                AlbumSource(
                    Groups(emptyMap()),
                    Wikidata(emptyMap()),
                    WikipediaClient(Wikipedia()),
                    names,
                    web = web,
                    webKept = webKept,
                )
            checkNotNull(source.searchWeb(classical))
            assertEquals(listOf(classical.performers), web.performers)
            // Another recording of the same title by the same composer is searched for itself.
            val shelley = classical.copy(performers = listOf("Howard Shelley", "London Mozart Players"))
            assertTrue(source.lookup(shelley).webDue)
            checkNotNull(source.searchWeb(shelley))
            assertEquals(2, web.asked.size)
            assertEquals(2, webKept.keys(AlbumSource.WEB_PART).size)
            // Neither is the album without performers, whose key stays as it was.
            assertTrue(source.lookup(classical.copy(performers = emptyList())).webDue)
        }
}
