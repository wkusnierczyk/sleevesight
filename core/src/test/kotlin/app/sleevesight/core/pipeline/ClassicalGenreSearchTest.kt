package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The AI's genre web searches (#1179): a title that leads with its credited composer is searched
 * without the lead, as every source is; any other title as before.
 */
class ClassicalGenreSearchTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun track(
        title: String,
        artists: String,
    ): TrackState {
        val playing = nowPlaying(title, artists)
        return TrackState(playing, normalizer.normalize(playing), emptyMap())
    }

    private val k545 =
        track(
            "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            "Wolfgang Amadeus Mozart, Khatia Buniatishvili",
        )
    private val blackOak = track("The Black Oak", "Emily Jane White")
    private val starWars = track("Star Wars: Main Title", "John Williams, London Symphony Orchestra")
    private val title = "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"

    private fun reasonQuery(track: TrackState): String =
        ReasonQuestion(track, "Classical", EvidenceSet.of(emptyList(), JvmHasher), false, JvmHasher, RETRIEVED).query

    @Test
    fun `the genre reason's search leaves out the composer's lead, any other title as before`() {
        assertEquals(
            "\"$title\" by Wolfgang Amadeus Mozart, Khatia Buniatishvili, Classical genre",
            reasonQuery(k545),
        )
        assertEquals("\"The Black Oak\" by Emily Jane White, Classical genre", reasonQuery(blackOak))
        assertEquals(
            "\"Star Wars: Main Title\" by John Williams, London Symphony Orchestra, Classical genre",
            reasonQuery(starWars),
        )
    }

    @Test
    fun `the genres list's search leaves out the composer's lead, any other title as before`() {
        assertEquals(
            "\"$title\" by Wolfgang Amadeus Mozart, Khatia Buniatishvili, song genre",
            GenreLists.genresQuery(k545.normalized),
        )
        assertEquals("\"The Black Oak\" by Emily Jane White, song genre", GenreLists.genresQuery(blackOak.normalized))
        assertEquals(
            "\"Star Wars: Main Title\" by John Williams, London Symphony Orchestra, song genre",
            GenreLists.genresQuery(starWars.normalized),
        )
    }
}
