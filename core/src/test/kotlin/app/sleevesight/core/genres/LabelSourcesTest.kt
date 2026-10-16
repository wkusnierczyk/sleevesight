package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelSourcesTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun lastFm(vararg tags: String) = vocabulary.labelsFor(tags.toList(), LASTFM_PAGE)

    private fun musicBrainz(vararg tags: String) = vocabulary.labelsFor(tags.toList(), MB_PAGE)

    @Test
    fun `Last fm's labels come first, then MusicBrainz's not already given`() {
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.MUSICBRAINZ to musicBrainz("country", "folk"),
                    LabelSource.LASTFM to lastFm("folk", "singer-songwriter"),
                ),
            )
        assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), combined.map { it.genre.label })
        assertEquals(
            listOf(
                listOf(LabelCredit(LabelSource.LASTFM, LASTFM_PAGE), LabelCredit(LabelSource.MUSICBRAINZ, MB_PAGE)),
                listOf(LabelCredit(LabelSource.LASTFM, LASTFM_PAGE)),
                listOf(LabelCredit(LabelSource.MUSICBRAINZ, MB_PAGE)),
            ),
            combined.map { it.credits },
        )
    }

    @Test
    fun `a label only MusicBrainz gives opens no genre card, one Last fm also gives does`() {
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to lastFm("rock"),
                    LabelSource.MUSICBRAINZ to musicBrainz("rock", "pop"),
                ),
            )
        assertEquals(listOf(true, false), combined.map { it.opensCard })
    }

    @Test
    fun `spellings of one genre from two sources are one label, as the first source spells it`() {
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to lastFm("hip-hop", "r&b"),
                    LabelSource.MUSICBRAINZ to musicBrainz("hip hop", "rnb"),
                ),
            )
        assertEquals(listOf("Hip-Hop", "R&B"), combined.map { it.genre.label })
        assertTrue(combined.all { it.credits.size == 2 })
    }

    @Test
    fun `at most five labels, and a full list still credits MusicBrainz where it agrees`() {
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to lastFm("rock", "pop", "folk", "jazz", "blues"),
                    LabelSource.MUSICBRAINZ to musicBrainz("soul", "blues"),
                ),
            )
        assertEquals(listOf("Rock", "Pop", "Folk", "Jazz", "Blues"), combined.map { it.genre.label })
        assertEquals(
            listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
            combined.last().credits.map { it.source },
        )
    }

    @Test
    fun `one source alone, or none`() {
        val alone = combineLabels(mapOf(LabelSource.MUSICBRAINZ to musicBrainz("ambient", "techno")))
        assertEquals(listOf("Ambient", "Techno"), alone.map { it.genre.label })
        assertFalse(alone.any { it.opensCard })
        assertEquals(emptyList(), combineLabels(emptyMap()))
        assertEquals(emptyList(), combineLabels(mapOf(LabelSource.LASTFM to emptyList())))
    }

    @Test
    fun `MusicBrainz is credited as its licence asks`() {
        // `design/architecture.md`, *MusicBrainz tags and genres (#9)*: "MusicBrainz contributors, CC BY-NC-SA 3.0".
        assertEquals("MusicBrainz contributors, CC BY-NC-SA 3.0", LabelSource.MUSICBRAINZ.credit)
        assertEquals("https://creativecommons.org/licenses/by-nc-sa/3.0/", LabelSource.MUSICBRAINZ.licenceUrl)
    }

    private companion object {
        const val LASTFM_PAGE = "https://www.last.fm/music/Gordon+Lightfoot"
        const val MB_PAGE = "https://musicbrainz.org/artist/mb-gl"
    }
}
