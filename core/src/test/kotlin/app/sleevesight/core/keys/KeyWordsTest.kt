package app.sleevesight.core.keys

import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KeyWordsTest {
    private val list = KeyWords.load(JvmResourceReader)

    @Test
    fun `the bundled list is the EFF's short wordlist 2, as published`() {
        val text = checkNotNull(JvmResourceReader.text(KeyWords.RESOURCE))
        val hash =
            JvmHasher.sha256(text.encodeToByteArray()).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        assertEquals(KeyWords.SHA256, hash)
        assertEquals(KeyWords.SIZE, list.words.size)
        assertEquals("aardvark", list.words.first())
        assertEquals("zucchini", list.words.last())
    }

    @Test
    fun `three letters complete a word, and a word typed wrong after them is none`() {
        assertEquals("aardvark", list.complete("aar"))
        assertEquals("aardvark", list.complete("  AardV "))
        assertEquals("aardvark", list.complete("aardvark"))
        assertNull(list.complete("aardvarks"))
        assertNull(list.complete("aardvarx"))
        assertNull(list.complete("aa"))
        assertNull(list.complete("xyz"))
        assertEquals("yo-yo", list.complete("yo-"))
        assertEquals("acid", list.complete("aci"))
        assertNull(list.complete("acidic"))
    }

    @Test
    fun `what is typed is read word by word, at spaces, line breaks and commas`() {
        assertEquals(
            listOf("aardvark", "abandoned", "abbreviate", "abdomen", "abhorrence"),
            list.read("AAR, aba,abb\n abd\tabhorrence "),
        )
        assertEquals(listOf(null, "abandoned"), list.read("aardvarx aba"))
        // Unicode's spaces split and trim, as JavaScript's `\s` and `trim` do on the page.
        assertEquals(
            listOf("aardvark", "abandoned", "abbreviate", "abdomen", "abhorrence"),
            list.read("\u00a0aar\u00a0aba\u3000abb\uFEFFabd\u2028abh\u00a0"),
        )
        assertEquals("aardvark", list.complete("\uFEFFaar\u00a0"))
        assertEquals(emptyList(), list.read("  "))
    }

    @Test
    fun `five different words are drawn, each from the list`() {
        // Picks that repeat: the second 0 is drawn again, so the words are five different ones.
        val picks = ArrayDeque(listOf(0, 0, 1, 1295, 2, 700))
        assertEquals(
            listOf(list.words[0], list.words[1], list.words[1295], list.words[2], list.words[700]),
            list.draw { bound ->
                assertEquals(KeyWords.SIZE, bound)
                picks.removeFirst()
            },
        )
    }

    @Test
    fun `a list without unique prefixes is refused`() {
        assertFailsWith<IllegalArgumentException> { KeyWords(list.words.dropLast(1) + "aardwolf") }
        assertFailsWith<IllegalArgumentException> { KeyWords(list.words.dropLast(1)) }
    }
}
