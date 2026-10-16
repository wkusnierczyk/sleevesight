package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
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
    fun `a credit led by the artist in the usual band forms, and nothing looser`() {
        assertTrue(names.leads("Dick Dale and His Del‐Tones", "Dick Dale"))
        assertTrue(names.leads("Ella Fitzgerald and Her Famous Orchestra", "Ella Fitzgerald"))
        assertTrue(names.leads("Bob Marley & the Wailers", "Bob Marley"))
        assertTrue(names.leads("Bob Marley and The Wailers", "bob marley"))
        assertTrue(names.leads("Sonny Boy Williamson with Big Joe Williams", "Sonny Boy Williamson"))
        assertTrue(names.leads("The Ray Johnson and His Combo", "Ray Johnson"))
        // A different leader; a name that only starts the same; the artist alone; no band named.
        assertFalse(names.leads("Y and His Del-Tones", "Dick Dale"))
        assertFalse(names.leads("Dick Daley and His Del-Tones", "Dick Dale"))
        assertFalse(names.leads("Dick Dale", "Dick Dale"))
        assertFalse(names.leads("Dick Dale and His", "Dick Dale"))
        // Not a band form: a duo, or a band whose name merely starts with the artist's.
        assertFalse(names.leads("Simon & Garfunkel", "Simon"))
        assertFalse(names.leads("Dick Dale Tribute Band", "Dick Dale"))
        assertFalse(names.leads("Dick Dale and His Del-Tones", ""))
    }

    @Test
    fun `a leading honorific on one name only is a variant, and nothing looser`() {
        // Track 50 (#79): MusicBrainz credits "Mr. A Balladeer", the player says "a balladeer".
        assertTrue(names.honorificVariant("Mr. A Balladeer", "a balladeer"))
        assertTrue(names.honorificVariant("a balladeer", "Mr A Balladeer"))
        assertTrue(names.honorificVariant("Mrs. Jane Smith", "Jane Smith"))
        // A variant is not the same name: the resolver weighs it apart.
        assertFalse(names.sameName("Mr. A Balladeer", "a balladeer"))
        // One word after the honorific is a stage name of its own.
        assertFalse(names.honorificVariant("Dr. John", "John"))
        assertFalse(names.honorificVariant("Mr. Big", "Big"))
        // A hyphenated word is one word, however the key splits it.
        assertFalse(names.honorificVariant("Mr. T-Bone", "T-Bone"))
        assertFalse(names.honorificVariant("Sir Mix-a-Lot", "Mix-A-Lot"))
        // Two different honorifics; the same name on both sides is a credit by name, not a variant.
        assertFalse(names.honorificVariant("Mr. Jane Smith", "Mrs. Jane Smith"))
        assertFalse(names.honorificVariant("Mr. A Balladeer", "Mr. A Balladeer"))
        // The rest must be the other name whole, and the first word a listed honorific.
        assertFalse(names.honorificVariant("Mr. A Balladeer", "Balladeer"))
        assertFalse(names.honorificVariant("Mr. A Balladeer Tribute", "a balladeer"))
        assertFalse(names.honorificVariant("Mr. A Balladeer", "a balladeers"))
        assertFalse(names.honorificVariant("MC A Balladeer", "a balladeer"))
        assertFalse(names.honorificVariant("Mr.", ""))
        assertFalse(names.honorificVariant("", ""))
    }

    @Test
    fun `title similarity is one for equal keys and falls with each edit`() {
        assertEquals(1.0, names.titleSimilarity("Here Comes The Sun", "Here Comes the Sun"))
        assertTrue(names.titleSimilarity("Sundown", "Sundown (Live)") < ResolutionThresholds.TITLE_SIMILARITY)
    }
}
