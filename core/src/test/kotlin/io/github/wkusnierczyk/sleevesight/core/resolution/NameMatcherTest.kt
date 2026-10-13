package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatcherTest {
    private val names = NameMatcher(JvmAccentFolder)

    @Test
    fun `keys ignore case, accents, punctuation and apostrophes`() {
        assertEquals("baby please dont go", names.key("Baby, Please Don’t Go"))
        assertEquals(names.key("Baby, Please Don't Go"), names.key("BABY PLEASE DON’T GO"))
        assertEquals("beyonce", names.key("Beyoncé"))
        assertEquals("simon and garfunkel", names.key("Simon & Garfunkel"))
    }

    @Test
    fun `names are the same apart from a leading the, and nothing looser`() {
        assertTrue(names.sameName("The Waterboys", "Waterboys"))
        assertTrue(names.sameName("Them", "THEM"))
        assertFalse(names.sameName("Reem", "Reema"))
        assertFalse(names.sameName("Jessiah", "Jesaiah"))
        assertFalse(names.sameName("", ""))
    }

    @Test
    fun `title similarity is one for equal keys and falls with each edit`() {
        assertEquals(1.0, names.titleSimilarity("Here Comes The Sun", "Here Comes the Sun"))
        assertTrue(names.titleSimilarity("Sundown", "Sundown (Live)") < ResolutionThresholds.TITLE_SIMILARITY)
    }
}
