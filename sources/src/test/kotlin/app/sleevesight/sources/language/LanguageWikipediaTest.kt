package app.sleevesight.sources.language

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.net.NetRequest
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.articleUrl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Wikipedia in the track's language (#902): its host, the articles Wikidata's sitelinks lead to,
 * and their sentences, each marked with the language.
 */
class LanguageWikipediaTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val normalizer = Normalizer(JvmAccentFolder)
    private val hindi = Languages.ofCode("hi")!!

    // Hindi Wikipedia's leads (CC BY-SA 4.0), shortened, fetched 2026-11-05.
    private val leads =
        mapOf(
            "तुम ही हो" to
                "तुम ही हो 2013 की सुपरहिट फिल्म आशिकी 2 का एक सुपरहिट गाना हैं। " +
                "आशिकी 2 का यह गाना फिल्म के लिए अरिजीत सिंह ने गाया है।",
            "आशिक़ी 2" to "आशिक़ी 2 मोहित सूरी द्वारा निर्देशित 2013 की बॉलीवुड संगीतमय प्रेमपरक नाटक फ़िल्म है।",
            "अरिजीत सिंह" to
                "अरिजीत सिंह (जन्म 25 अप्रैल 1987) एक भारतीय गायक और संगीतकार हैं। " +
                "इनका जन्म पश्चिम बंगाल के मुर्शिदाबाद में हुआ था।",
        )

    private fun extract(title: String): String =
        buildJsonObject {
            putJsonObject("query") {
                put(
                    "pages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("title", title)
                                leads[title]?.let { put("extract", it) } ?: put("missing", true)
                            },
                        )
                    },
                )
            }
        }.toString()

    private val net =
        RoutedNet { request: NetRequest ->
            if (request.url == WikipediaClient.apiOf("hi") && request.query["prop"] == "extracts") {
                extract(request.query.getValue("titles"))
            } else {
                null
            }
        }

    private val adapter =
        LanguageWikipedia(net, IdentityCheck(FakeWikidata(emptyMap()), NameMatcher(JvmAccentFolder)), clock)

    private val query =
        LanguageQuery(
            normalizer.normalize("Tum Hi Ho", "Arijit Singh"),
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = "rec",
                artists = listOf(ResolvedArtist("Arijit Singh", "mb-arijit")),
            ),
            hindi,
        )

    private val items =
        LanguageItems(
            song = LocalName("Q15322556", "तुम ही हो", "तुम ही हो"),
            comes = listOf(LocalName("Q4662712", "आशिक़ी 2", "आशिक़ी 2", "Aashiqui 2")),
            artists = mapOf("Arijit Singh" to LocalName("Q4790608", "अरिजीत सिंह", "अरिजीत सिंह")),
        )

    @Test
    fun `the extraction version is 3, raised for the resolutions #1129 and #1203 changed`() {
        // The pages are checked against the resolution, which #1129 and #1203 changed: results kept
        // under an earlier version, against an earlier choice, are asked again.
        assertEquals(3, adapter.extractionVersion)
    }

    @Test
    fun `the language's Wikipedia is asked, on its own host`() =
        runTest {
            adapter.lookup(query, items)
            assertEquals(setOf("https://hi.wikipedia.org/w/api.php"), net.sent.map { it.url }.toSet())
            assertEquals(listOf("तुम ही हो", "आशिक़ी 2", "अरिजीत सिंह"), net.sent.map { it.query["titles"] })
        }

    @Test
    fun `the song's, the film's and the artist's sentences, each marked Hindi and linking its article there`() =
        runTest {
            val result = adapter.lookup(query, items)
            assertEquals(Outcome.SongArticle, result.outcome)
            // Each lead's first sentence: the others name neither the song nor the artist in the script.
            assertEquals(listOf(Scope.SONG, Scope.RELEASE, Scope.ARTIST), result.evidence.map { it.scope })
            assertTrue(
                result.evidence.all {
                    it.source == SourceId.WIKIPEDIA &&
                        it.language == "hi" &&
                        it.retrievedAt == now
                },
            )
            // The film's line is about the film by its English name, the album insight's key to it.
            assertEquals(listOf(null, "Aashiqui 2", "Arijit Singh"), result.evidence.map { it.about })
            assertEquals(articleUrl("आशिक़ी 2", "hi"), result.evidence[1].url)
            assertTrue(result.evidence[1].url!!.startsWith("https://hi.wikipedia.org/wiki/%E0%A4%86"))
            assertEquals(
                "आशिक़ी 2 मोहित सूरी द्वारा निर्देशित 2013 की बॉलीवुड संगीतमय प्रेमपरक नाटक फ़िल्म है।",
                result.evidence[1].text,
            )
            assertEquals(false, result.provisional)
            assertEquals(false, result.trackOnly)
        }

    @Test
    fun `no article in the language gives nothing, and a failed lead is unreachable and not kept`() =
        runTest {
            assertEquals(Outcome.None, adapter.lookup(query, LanguageItems(song = LocalName("Q1", "x", null))).outcome)
            val down =
                LanguageWikipedia(
                    RoutedNet { null },
                    IdentityCheck(FakeWikidata(emptyMap()), NameMatcher(JvmAccentFolder)),
                    clock,
                )
            val result = down.lookup(query, items)
            assertEquals(Outcome.Unreachable, result.outcome)
            assertTrue(result.provisional)
        }

    @Test
    fun `a song without a sitelink is found by its title in the script, if the check confirms its item`() =
        runTest {
            val tamil = Languages.ofCode("ta")!!
            val title = "கண்ணான கண்ணே"
            val song =
                WdEntity(
                    "Q100",
                    "Kannaana Kanne",
                    null,
                    mapOf(Wd.INSTANCE_OF to listOf("Q7366"), Wd.PERFORMER to listOf("Q200")),
                )
            val singer = WdEntity("Q200", "Sid Sriram", null, emptyMap())
            val check =
                IdentityCheck(FakeWikidata(mapOf("Q100" to song, "Q200" to singer)), NameMatcher(JvmAccentFolder))
            val routed =
                RoutedNet { request ->
                    when {
                        request.url != WikipediaClient.apiOf("ta") -> null
                        request.query["prop"] == "pageprops" ->
                            """{"query":{"pages":[{"title":"$title","pageprops":{"wikibase_item":"Q100"}}]}}"""
                        else ->
                            """{"query":{"pages":[{"title":"$title","extract":"$title 2019 இல் வெளியான பாடல்."}]}}"""
                    }
                }
            val result =
                LanguageWikipedia(routed, check, clock).lookup(
                    LanguageQuery(normalizer.normalize(title, "Sid Sriram"), Resolution.NONE, tamil),
                    LanguageItems.NONE,
                )
            assertEquals(listOf("$title 2019 இல் வெளியான பாடல்."), result.evidence.map { it.text })
            assertEquals(listOf("ta"), result.evidence.map { it.language })
            assertTrue(
                result.evidence
                    .single()
                    .url!!
                    .startsWith("https://ta.wikipedia.org/wiki/"),
            )
        }

    @Test
    fun `a work's sentences do not depend on the song playing, so its album's fingerprint does not either`() =
        runTest {
            // The film's name in the first and last sentences; one song's alone in the third.
            val lead =
                "आशिक़ी 2 2013 की फ़िल्म है। इसका संगीत मिथुन ने दिया। तुम ही हो इसका एक गाना है। " +
                    "आशिक़ी 2 सफल रही।"
            val net =
                RoutedNet { request ->
                    """{"query":{"pages":[{"title":"${request.query["titles"]}","extract":"$lead"}]}}"""
                }
            val adapter =
                LanguageWikipedia(net, IdentityCheck(FakeWikidata(emptyMap()), NameMatcher(JvmAccentFolder)), clock)
            val film = LanguageItems(comes = listOf(LocalName("Q4662712", "आशिक़ी 2", "आशिक़ी 2", "Aashiqui 2")))

            suspend fun releaseOf(title: String): EvidenceSet {
                val query = LanguageQuery(normalizer.normalize(title, "Artist"), Resolution.NONE, hindi)
                val lines = adapter.lookup(query, film).evidence.filter { it.scope == Scope.RELEASE }
                return EvidenceSet.of(lines, JvmHasher)
            }
            val one = releaseOf("तुम ही हो")
            val other = releaseOf("सुन रहा है")
            assertEquals(listOf("आशिक़ी 2 2013 की फ़िल्म है।", "आशिक़ी 2 सफल रही।"), one.lines.map { it.text })
            assertEquals(one.lines.map { it.text }, other.lines.map { it.text })
            assertEquals(one.fingerprint, other.fingerprint)
        }
}
