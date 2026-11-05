package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The web search in the track's language (#902): its query in the language, the same excludes as the
 * English search's, and its results kept and marked. Synthetic results; no Exa call.
 */
class ExaLanguageSearchTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val normalizer = Normalizer(JvmAccentFolder)
    private val hindi = Languages.ofCode("hi")!!

    private val query = LanguageQuery(normalizer.normalize("Tum Hi Ho", "Arijit Singh"), Resolution.NONE, hindi)
    private val items =
        LanguageItems(
            song = LocalName("Q15322556", "तुम ही हो", "तुम ही हो"),
            comes = listOf(LocalName("Q4662712", "आशिक़ी 2", "आशिक़ी 2")),
            artists = mapOf("Arijit Singh" to LocalName("Q4790608", "अरिजीत सिंह", null)),
        )

    private fun response(vararg results: Triple<String, String, String>): String =
        results.joinToString(",", prefix = """{"results":[""", postfix = "]}") { (url, title, highlight) ->
            """{"url":"$url","title":"$title","highlights":["$highlight"]}"""
        }

    private fun body(net: ReplayNetClient) =
        Json
            .parseToJsonElement(
                net.sent
                    .single()
                    .body!!
                    .text,
            ).jsonObject

    @Test
    fun `the query names the song, its film and its artist in the language's script, with its word for a song`() =
        runTest {
            val net = ReplayNetClient.ok(response())
            ExaLanguageSearch(net, Secret("test-exa-key-0123456789"), JvmAccentFolder, clock).lookup(query, items)
            val body = body(net)
            assertEquals("\"तुम ही हो\" आशिक़ी 2 अरिजीत सिंह, गीत", body.getValue("query").jsonPrimitive.content)
            assertEquals("lite", body.getValue("type").jsonPrimitive.content)
            assertEquals(
                RecordingFilter.OWN_SOURCE_HOSTS,
                body.getValue("excludeDomains").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue("wikipedia.org" in RecordingFilter.OWN_SOURCE_HOSTS)
            assertEquals(
                "तुम ही हो आशिक़ी 2 अरिजीत सिंह",
                body
                    .getValue(
                        "contents",
                    ).jsonObject
                    .getValue("highlights")
                    .jsonObject
                    .getValue("query")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "test-exa-key-0123456789",
                net.sent
                    .single()
                    .secrets.headers[ExaWebSearch.KEY_HEADER],
            )
        }

    @Test
    fun `without names in the script, the track's own go in the query`() {
        val portuguese = Languages.ofCode("pt")!!
        val track = normalizer.normalize("Evidências", "Chitãozinho & Xororó")
        val request = ExaLanguageQuery.of(LanguageQuery(track, Resolution.NONE, portuguese), LanguageItems.NONE)
        assertEquals("\"Evidências\" Chitãozinho & Xororó, canção", request.query)
        assertEquals(RecordingFilter.OWN_SOURCE_HOSTS, request.excludeDomains)
    }

    @Test
    fun `results about the recording by either name are kept, those in the script marked Hindi`() =
        runTest {
            val net =
                ReplayNetClient.ok(
                    response(
                        Triple(
                            "https://example.in/tum",
                            "तुम ही हो - अरिजीत सिंह",
                            "तुम ही हो गाना अरिजीत सिंह ने आशिक़ी 2 के लिए गाया है।",
                        ),
                        Triple(
                            "https://example.com/tum",
                            "Tum Hi Ho, Arijit Singh",
                            "Tum Hi Ho is sung by Arijit Singh for Aashiqui 2.",
                        ),
                        Triple("https://example.in/other", "कोई और गाना", "यह किसी और का गाना है।"),
                    ),
                )
            val result = ExaLanguageSearch(net, Secret("k"), JvmAccentFolder, clock).lookup(query, items)
            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals(listOf("https://example.in/tum", "https://example.com/tum"), result.evidence.map { it.url })
            assertEquals(listOf("hi", null), result.evidence.map { it.language })
            assertTrue(result.evidence.all { it.source == SourceId.WEB && it.retrievedAt == now })
            assertTrue(result.trackOnly)
        }

    @Test
    fun `in a script without spaces between words, a name is found inside the text`() =
        runTest {
            val japanese = Languages.ofCode("ja")!!
            val track = normalizer.normalize("夜に駆ける", "YOASOBI")
            val page = Triple("https://example.jp/yoru", "夜に駆けるYOASOBI", "夜に駆けるはYOASOBIの楽曲。")
            val filter = RecordingFilter(JvmAccentFolder)
            assertFalse(filter.isAbout(track, page.second, page.third, page.first), "whole words do not find it")
            val result =
                ExaLanguageSearch(ReplayNetClient.ok(response(page)), Secret("k"), JvmAccentFolder, clock)
                    .lookup(LanguageQuery(track, Resolution.NONE, japanese), LanguageItems.NONE)
            assertEquals(listOf("ja"), result.evidence.map { it.language })
        }

    @Test
    fun `a failed search is unreachable, and a track that cannot be checked sends nothing`() =
        runTest {
            val down = ExaLanguageSearch(ReplayNetClient.status(401, "{}"), Secret("k"), JvmAccentFolder, clock)
            assertEquals(Outcome.Unreachable, down.lookup(query, items).outcome)
            val net = ReplayNetClient.ok(response())
            val unchecked = LanguageQuery(normalizer.normalize("!!!", "!!!"), Resolution.NONE, hindi)
            assertEquals(
                Outcome.None,
                ExaLanguageSearch(
                    net,
                    Secret("k"),
                    JvmAccentFolder,
                    clock,
                ).lookup(unchecked, LanguageItems.NONE).outcome,
            )
            assertNull(net.sent.firstOrNull())
        }
}
