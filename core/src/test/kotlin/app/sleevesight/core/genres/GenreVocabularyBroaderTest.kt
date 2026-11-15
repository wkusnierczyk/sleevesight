package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A genre's broader genre (#1127): the vocabulary's `broader`, from MusicBrainz's genre relationships
 * (*subgenre of*, CC0), gives each genre it lists the broader genre's label, which the vocabulary
 * holds; none is its own, and none goes round.
 */
class GenreVocabularyBroaderTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun `Contemporary R&B is a style of R&B, which the vocabulary names R&B`() {
        val broader = vocabulary.broader(checkNotNull(vocabulary.identity("contemporary r&b")))
        assertEquals("R&B", broader?.label)
        assertEquals(vocabulary.identity("rnb"), broader?.identity)
    }

    @Test
    fun `every spelling of a genre has its broader genre, and a genre with none has none`() {
        assertEquals(
            vocabulary.broader(checkNotNull(vocabulary.identity("goth rock"))),
            vocabulary.broader(checkNotNull(vocabulary.identity("gothic rock"))),
        )
        assertNull(vocabulary.broader(checkNotNull(vocabulary.identity("rock"))))
        assertNull(vocabulary.broader("not a genre"))
    }

    @Test
    fun `each broader genre is in the vocabulary, never the genre itself, and none goes round`() {
        val given = mutableSetOf<String>()
        for (tag in vocabulary.tags) {
            val identity = checkNotNull(vocabulary.identity(tag))
            val broader = vocabulary.broader(identity) ?: continue
            given += identity
            assertEquals(broader.identity, vocabulary.identity(broader.tag))
            assertNotEquals(identity, broader.identity)
            val seen = mutableSetOf(identity)
            var at: GenreLabel? = broader
            while (at != null) {
                assertTrue(seen.add(at.identity), "$tag goes round at ${at.identity}")
                at = vocabulary.broader(at.identity)
            }
        }
        // Every entry of the file's `broader` is given, as written, and no genre beyond them.
        val file =
            Json
                .parseToJsonElement(checkNotNull(JvmResourceReader.text(GenreVocabulary.RESOURCE)))
                .jsonObject
                .getValue("broader")
                .jsonObject
        for ((tag, wider) in file) {
            val identity = checkNotNull(vocabulary.identity(tag)) { tag }
            assertEquals(vocabulary.identity(wider.jsonPrimitive.content), vocabulary.broader(identity)?.identity, tag)
        }
        assertEquals(file.keys.mapNotNull(vocabulary::identity).toSet(), given)
    }

    @Test
    fun `a vocabulary whose broader genres are not its own, or go round, is refused`() {
        val tags = listOf("rock", "punk", "punk rock")
        GenreVocabulary(tags, broader = mapOf("punk rock" to "rock"))
        assertFailsWith<IllegalArgumentException> { GenreVocabulary(tags, broader = mapOf("punk rock" to "jazz")) }
        assertFailsWith<IllegalArgumentException> { GenreVocabulary(tags, broader = mapOf("rock" to "rock")) }
        assertFailsWith<IllegalArgumentException> {
            GenreVocabulary(tags, broader = mapOf("punk rock" to "punk", "punk" to "punk rock"))
        }
    }
}
