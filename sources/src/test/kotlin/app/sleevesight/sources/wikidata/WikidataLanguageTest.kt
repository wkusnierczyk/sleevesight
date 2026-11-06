package app.sleevesight.sources.wikidata

import app.sleevesight.core.language.Languages
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.sources.language.RoutedNet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Wikidata's items with their names in the track's language (#902): the parameters, and what is read. */
class WikidataLanguageTest {
    private val body =
        """{"entities":{"Q15322556":{"id":"Q15322556",
          "labels":{"en":{"language":"en","value":"Tum Hi Ho"},"hi":{"language":"hi","value":"तुम ही हो"}},
          "sitelinks":{"enwiki":{"site":"enwiki","title":"Tum Hi Ho"},"hiwiki":{"site":"hiwiki","title":"तुम ही हो"}},
          "claims":{"P407":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q1568"}}}}]}}}}"""

    @Test
    fun `labels and sitelinks are asked in English and the language`() =
        runTest {
            val net = RoutedNet { body }
            val found = WikidataClient(net).entities(listOf("Q15322556"), Languages.ofCode("hi")!!)
            val query = net.sent.single().query
            assertEquals("en|hi", query["languages"])
            assertEquals("enwiki|hiwiki", query["sitefilter"])
            assertEquals("claims|sitelinks|labels", query["props"])
            val item = (found as Lookup.Found).value.getValue("Q15322556")
            assertEquals(mapOf("en" to "Tum Hi Ho", "hi" to "तुम ही हो"), item.labels)
            assertEquals(mapOf("enwiki" to "Tum Hi Ho", "hiwiki" to "तुम ही हो"), item.sitelinks)
            assertEquals("Tum Hi Ho", item.enwiki)
            assertEquals(listOf("Q1568"), item.values("P407"))
        }

    @Test
    fun `a language whose Wikipedia's code differs keeps both apart`() =
        runTest {
            val net = RoutedNet { """{"entities":{}}""" }
            WikidataClient(net).entities(listOf("Q1"), Languages.ofCode("yue")!!)
            assertEquals("en|yue", net.sent.single().query["languages"])
            assertEquals("enwiki|zh_yuewiki", net.sent.single().query["sitefilter"])
        }

    @Test
    fun `the English lookup's request is as it was`() =
        runTest {
            val net = RoutedNet { """{"entities":{}}""" }
            WikidataClient(net).entities(listOf("Q1"))
            assertEquals("en", net.sent.single().query["languages"])
            assertEquals("enwiki", net.sent.single().query["sitefilter"])
        }
}
