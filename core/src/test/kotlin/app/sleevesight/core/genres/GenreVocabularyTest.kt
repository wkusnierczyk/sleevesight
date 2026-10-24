package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.fixtures.BelowSoundtrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenreVocabularyTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun `the family hues are the design's`() {
        // `design/design.md`, *Genre labels*.
        val hues = vocabulary.families.associate { it.id to it.hue }
        assertEquals(
            mapOf(
                "folk" to 135,
                "rock" to 8,
                "pop" to 328,
                "hiphop" to 30,
                "soul" to 48,
                "reggae" to 92,
                "electronic" to 190,
                "score" to 262,
                "jazzblues" to 215,
            ),
            hues,
        )
    }

    @Test
    fun `tags outside the vocabulary are dropped and the order is the source's`() {
        val labels = vocabulary.labelsFor(listOf("folk", "singer-songwriter", "70s", "canadian", "acoustic"))
        assertEquals(listOf("Folk", "Singer-Songwriter", "Acoustic"), labels.map { it.label })
        assertEquals(listOf("folk", "folk", "folk"), labels.map { it.family })
    }

    @Test
    fun `tags are matched lowercased and trimmed, once each, at most five`() {
        val labels =
            vocabulary.labelsFor(listOf(" Rock ", "rock", "Hip-Hop", "hip hop", "jazz", "blues", "soul", "funk", "pop"))
        assertEquals(listOf("rock", "hip-hop", "jazz", "blues", "soul"), labels.map { it.tag })
        assertEquals(GenreVocabulary.MAX_LABELS, labels.size)
    }

    @Test
    fun `labels are title case, with the vocabulary's overrides`() {
        assertEquals(
            listOf("R&B", "UK Garage", "Post-Punk", "Chanson Francaise"),
            vocabulary.labelsFor(listOf("r&b", "uk garage", "post-punk", "chanson francaise")).map { it.label },
        )
        assertEquals(listOf("RnB"), vocabulary.labelsFor(listOf("rnb")).map { it.label })
    }

    @Test
    fun `spellings of one genre give one label, the first`() {
        assertEquals(listOf("RnB", "Soul"), vocabulary.labelsFor(listOf("rnb", "r&b", "soul")).map { it.label })
        assertEquals(vocabulary.identity("r&b"), vocabulary.identity("RnB"))
        assertEquals(vocabulary.identity("hip-hop"), vocabulary.identity("hip hop"))
        assertEquals(null, vocabulary.identity("seen live"))
    }

    @Test
    fun `each label carries its genre's identity and the page its tags are from`() {
        val page = "https://www.last.fm/music/Ann+Peebles"
        val labels = vocabulary.labelsFor(listOf("r&b", "hip hop", "soul"), page)
        assertEquals(listOf("rb", "hiphop", "soul"), labels.map { it.identity })
        assertEquals(labels.map { vocabulary.identity(it.tag) }, labels.map { it.identity })
        assertEquals(listOf(page), labels.map { it.url }.distinct())
        assertEquals(null, vocabulary.labelsFor(listOf("soul")).single().url)
    }

    @Test
    fun `noise gives no labels`() {
        val noise = listOf("seen live", "bagel", "under 2000 listeners", "denmark", "00s")
        assertTrue(vocabulary.labelsFor(noise).isEmpty())
    }

    @Test
    fun `the fixture's Last fm tags leave 30 of 78 tracks without labels, as the design says`() {
        // `design/design.md`, *Genre labels*: "30 of 78 unique tracks got no genre labels".
        val facts = BelowSoundtrack.facts
        assertEquals(78, facts.size)
        assertEquals(30, facts.count { vocabulary.labelsFor(it.lastFm.tags).isEmpty() })
    }

    @Test
    fun `a malformed vocabulary is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GenreVocabulary(listOf(GenreFamily("a", 10, listOf("rock")), GenreFamily("b", 20, listOf("rock"))))
        }
        assertFailsWith<IllegalArgumentException> { GenreVocabulary(listOf(GenreFamily("a", 360, listOf("rock")))) }
        assertFailsWith<IllegalArgumentException> { GenreVocabulary(listOf(GenreFamily("a", 10, listOf("Rock")))) }
    }

    @Test
    fun `agreement counts labels from each source and from both`() {
        val agreement = vocabulary.agreement(listOf("folk", "70s"), listOf("folk", "country", "canadian"))
        assertEquals(GenreAgreement(lastFm = 1, musicBrainz = 2, both = 1), agreement)
        assertEquals(false, agreement.fillsGap)
        assertEquals(true, vocabulary.agreement(listOf("seen live"), listOf("rock")).fillsGap)
    }

    @Test
    fun `agreement counts spellings of one genre as the same label`() {
        assertEquals(GenreAgreement(1, 1, 1), vocabulary.agreement(listOf("hip-hop"), listOf("hip hop")))
        assertEquals(GenreAgreement(1, 1, 1), vocabulary.agreement(listOf("r&b"), listOf("rnb")))
    }
}
