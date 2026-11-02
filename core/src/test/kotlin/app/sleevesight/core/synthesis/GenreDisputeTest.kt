package app.sleevesight.core.synthesis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A reason that says the track is not the genre it was asked to explain (#853). */
class GenreDisputeTest {
    private val folk = setOf("folk")

    @Test
    fun `the reply seen on the phone disputes Folk`() {
        // "Runnin'" by Frazey Ford, Folk only from MusicBrainz, the evidence giving Americana (#853).
        val reply =
            "Frazey Ford is a Canadian singer-songwriter [W1], and \"Runnin'\" is a 2014 track from her album " +
                "Indian Ocean [MB1][P1]. The only genre the evidence gives for her is Americana, not Folk [WD1]."
        assertTrue(GenreDispute.disputes(reply, folk))
    }

    @Test
    fun `a negation before the genre, or a few words that keep it there, disputes it`() {
        listOf(
            "It is not folk [W1].",
            "It isn't really folk [W1].",
            "It doesn't sound like folk music [W1].",
            "Her songs are neither rock nor folk [W1].",
            "It is Americana rather than folk [W1].",
            "It is not a folk song [W1].",
        ).forEach { assertTrue(GenreDispute.disputes(it, folk), it) }
    }

    @Test
    fun `a genre named without a negation on it is no dispute`() {
        listOf(
            "Her fingerpicked guitar and plain voice are folk [W1].",
            "It is not only folk but also soul [W1].",
            "It is never far from folk [W1].",
            "It is not unlike folk [W1].",
            "It is folk, not rock [W1].",
            "Its sound is not loud. Folk singers play it softly [W1].",
            "It is folklore-inspired [W1].",
        ).forEach { assertFalse(GenreDispute.disputes(it, folk), it) }
    }

    @Test
    fun `a genre of several words is found in any spelling of its label`() {
        val forms = setOf("hiphop", GenreDispute.fold("Hip Hop"))
        assertEquals(setOf("hiphop"), forms)
        assertTrue(GenreDispute.disputes("It is not hip-hop [W1].", forms))
        assertTrue(GenreDispute.disputes("It is not hip hop [W1].", forms))
        assertFalse(GenreDispute.disputes("It is hip hop [W1].", forms))
        assertFalse(GenreDispute.disputes("It is not hip [W1].", forms))
    }

    @Test
    fun `no forms dispute nothing`() {
        assertFalse(GenreDispute.disputes("It is not folk.", emptySet()))
    }
}
