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
            "It is not classified as folk [W1].",
            "It does not belong to folk [W1].",
            // #864's second review: the genre's name ends at a mark or a word that makes no longer genre.
            "It is not folk because its sound is rooted in soul [W1].",
            "It is Americana rather than folk, with a soul groove [W1].",
            "The evidence does not mention folk [W1].",
        ).forEach { assertTrue(GenreDispute.disputes(it, folk), it) }
    }

    @Test
    fun `the genre denied as the label in so many words disputes it`() {
        listOf(
            "Folk is not the right label for it [W1].",
            "Folk isn't an accurate genre for her [W1].",
            "The folk tag doesn't fit here [W1].",
            "Folk does not apply [W1].",
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
            // #864's review: a qualified genre, and another genre negated, are no dispute.
            "It is not purely folk: its acoustic storytelling blends with soul [W1].",
            "It is folk, not folk rock [W1].",
            "Folk is not loud here [W1].",
            "Folk is not the only thread in it [W1].",
            "Folk is no stranger to her [W1].",
            // #864's second review: a qualification of the label, not a denial of it.
            "Folk is not the whole story: its acoustic storytelling blends with soul [W1].",
            "The folk label does not describe every aspect of the track, but its acoustic storytelling fits [W1].",
            "The folk tag does not fit every song she wrote [W1].",
            // The Claude review of #864: the genre qualifying a noun, or hedged, is no dispute.
            "Ford is no folk purist: she blends folk storytelling with soul [W1].",
            "While it isn't folk in the traditional sense, its fingerpicked guitar carries folk's hallmarks [W1].",
            "It's not as folk as her debut, but its banjo is [W1].",
            "It is not folk alone: soul runs through it [W1].",
            "Folk is not the right label on its own: soul fits too [W1].",
        ).forEach { assertFalse(GenreDispute.disputes(it, folk), it) }
        assertFalse(GenreDispute.disputes("Though never a pop hit, its chorus is pop [W1].", setOf("pop")))
        assertFalse(GenreDispute.disputes("It isn't a jazz standard, but its harmony is jazz [W1].", setOf("jazz")))
    }

    @Test
    fun `the track's names are left out`() {
        val reply = "It closes her album No Pop [MB1]."
        assertTrue(GenreDispute.disputes(reply, setOf("pop")))
        assertFalse(GenreDispute.disputes(reply, setOf("pop"), names = listOf("no pop")))
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
