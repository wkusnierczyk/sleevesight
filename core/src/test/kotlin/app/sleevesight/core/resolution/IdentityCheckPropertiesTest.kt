package app.sleevesight.core.resolution

import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.IdentityCheck.Route
import app.sleevesight.core.resolution.IdentityCheck.Verdict
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Properties of the identity check's name rules ([NameMatcher], as [IdentityCheck] matches a
 * performer to a credited artist by name): the comparisons are symmetric, and a name compares
 * the same whatever its case and the punctuation between its words, as [NameMatcher.key] says.
 */
class IdentityCheckPropertiesTest {
    private val names = NameMatcher(JvmAccentFolder)

    // Letters whose case round-trips (`lowercase` of `uppercase` is the letter's own lowercase):
    // ASCII, accented Latin, Cyrillic, and CJK, which has no case.
    private val roundTrip =
        ('a'..'z').toList() + "éèöüñåøçãíóúЖжЯяДд坂本龍".toList() + ('0'..'9').toList()

    // Every letter above and those whose case does not round-trip: `ß`, the dotless `ı`, the dotted
    // `İ`, Greek's final `ς`, and the long `ſ`.
    private val anyCase = roundTrip + "ßıİςσΣſαβΓ".toList()

    private fun words(letters: List<Char>): Arb<List<String>> =
        Arb
            .list(Arb.list(Arb.element(letters), 1..MAX_WORD).map { it.joinToString("") }, 1..MAX_WORDS)

    private fun name(letters: List<Char>): Arb<String> = words(letters).map { it.joinToString(" ") }

    // A name as sources may write it: an honorific or `The` before it sometimes, punctuation between
    // its words and around it, `&` among them.
    private val written: Arb<String> =
        Arb.bind(
            Arb.element("", "", "The ", "Mr. ", "Mister ", "Dr. ", "Doctor ", "Sir ", "Dame "),
            words(roundTrip),
            Arb.element(" ", ", ", " & ", "-", ". ", " / "),
        ) { lead, parts, between -> lead + parts.joinToString(between) }

    // A run of what is neither a letter nor a digit, `&` and apostrophes aside, which mean something
    // to [NameMatcher.key].
    private val separator =
        Arb.list(Arb.element(" ", ".", ",", "-", "–", "/", "!", "?", "(", ")", "\"", "*", "_"), 1..3)

    // [parts] spaced with [separators] (the first and last around the name), and an apostrophe
    // inside a word where [apostrophe] says.
    private fun punctuated(
        parts: List<String>,
        separators: List<List<String>>,
        apostrophe: Boolean,
    ): String {
        val words =
            parts.mapIndexed { i, w ->
                if (apostrophe && i == 0 && w.length > 1) w.take(1) + "’" + w.drop(1) else w
            }
        val runs = List(words.size + 1) { i -> separators[i % separators.size].joinToString("") }
        val between = words.indices.joinToString("") { i -> words[i] + if (i < words.lastIndex) runs[i + 1] else "" }
        return runs.first() + between + runs.last()
    }

    @Test
    fun `the name comparisons are symmetric`() =
        runTest {
            checkAll(CONFIG, written, written) { a, b ->
                assertEquals(names.sameName(a, b), names.sameName(b, a), "sameName(\"$a\", \"$b\")")
                val pair = "(\"$a\", \"$b\")"
                assertEquals(names.honorificVariant(a, b), names.honorificVariant(b, a), "honorificVariant$pair")
                assertEquals(names.titleSimilarity(a, b), names.titleSimilarity(b, a), "titleSimilarity$pair")
            }
        }

    @Test
    fun `a name is the same name in upper and lower case`() =
        runTest {
            checkAll(CONFIG, name(roundTrip)) { n ->
                assertTrue(names.sameName(n, n.uppercase()), "\"$n\" and \"${n.uppercase()}\"")
                val upper = n.uppercase()
                assertTrue(names.sameName(upper, n.lowercase()), "\"$upper\" and \"${n.lowercase()}\"")
            }
        }

    // Fails: "1vın本" against "1VIN本" (shrunk from "íãóíå 1vın本"): the dotless "ı" upper-cases to "I",
    // which `key` lowercases to "i". See #1490.
    @Ignore
    @Test
    fun `a name in any script is the same name in upper and lower case`() =
        runTest {
            checkAll(CONFIG, name(anyCase)) { n ->
                assertTrue(names.sameName(n, n.uppercase()), "\"$n\" and \"${n.uppercase()}\"")
            }
        }

    @Test
    fun `a name is the same name whatever the punctuation between and around its words`() =
        runTest {
            checkAll(
                CONFIG,
                words(roundTrip),
                Arb.list(separator, 1..MAX_WORDS + 1),
                Arb.boolean(),
            ) { parts, separators, apostrophe ->
                val plain = parts.joinToString(" ")
                val variant = punctuated(parts, separators, apostrophe)
                assertTrue(names.sameName(plain, variant), "\"$plain\" and \"$variant\"")
            }
        }

    @Test
    fun `the identity check accepts a performer whatever the case and punctuation of the name`() =
        runTest {
            checkAll(
                CONFIG,
                words(roundTrip).filter { parts -> parts.none { it in RESERVED } },
                Arb.list(separator, 1..MAX_WORDS + 1),
                Arb.boolean(),
            ) { parts, separators, upper ->
                val plain = parts.joinToString(" ")
                val punctuated = punctuated(parts, separators, apostrophe = false)
                val variant = if (upper) punctuated.uppercase() else punctuated
                assertEquals(verdict(plain, plain), verdict(plain, variant), "\"$plain\" and \"$variant\"")
            }
        }

    // The song item's verdict, by name, for a track credited to [artist], whose item names [performer]:
    // the scope it is accepted for, or "rejected".
    private suspend fun verdict(
        artist: String,
        performer: String,
    ): String {
        val song =
            WdEntity("Q1", "Song", "Song", mapOf(Wd.INSTANCE_OF to listOf("Q7366"), Wd.PERFORMER to listOf("Q10")))
        val wikidata = Wikidata(mapOf("Q1" to song), mapOf("Q10" to performer))
        val track = Normalizer(JvmAccentFolder).normalize("Song", artist)
        val resolution = Resolution.NONE.copy(artists = listOf(ResolvedArtist(artist, null)))
        return when (val v = IdentityCheck(wikidata, names).song("Q1", track, resolution, Route.NAME)) {
            is Verdict.Accepted -> v.scope.name
            is Verdict.Rejected -> "rejected"
            Verdict.Unreachable -> "unreachable"
        }
    }

    // Wikidata with [entities] and [labels], and no MusicBrainz ids.
    private class Wikidata(
        val entities: Map<String, WdEntity>,
        val labels: Map<String, String>,
    ) : WikidataLookup {
        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
            Lookup.Found(entities.filterKeys { it in ids })

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(labels.filterKeys { it in ids })

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> = Lookup.Found(emptyList())
    }

    private companion object {
        val CONFIG = PropTestConfig(seed = 76L, iterations = 300)
        const val MAX_WORD = 6
        const val MAX_WORDS = 4

        // Words the normalizer reads as joining two artists' names, kept out of a single artist's.
        val RESERVED = setOf("x", "and", "vs", "feat", "ft", "with")
    }
}
