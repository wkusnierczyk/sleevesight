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

    // A source's tags with their counts, uncapped, as the label sources give them (#1019).
    private fun counted(
        page: String,
        vararg tags: Pair<String, Int>,
    ) = vocabulary.sourceLabels(tags.map { (name, count) -> CountedTag(name, count) }, page, GenreLevel.ARTIST)

    @Test
    fun `labels without counts weigh by their place, a genre both sources give the sum`() {
        // Folk: Last.fm's first (1) and MusicBrainz's second of two (0.5); Country: MusicBrainz's
        // first (1), MusicBrainz's alone, so counted at half; Singer-Songwriter: Last.fm's second of
        // two (0.5), given first at that weight.
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
    fun `at most five labels, the cap after the sources combine, and a dropped label's agreement still counts`() {
        // Last.fm's five by place: 1, 0.8, 0.6, 0.4, 0.2; MusicBrainz's Soul 1, counted at half as
        // MusicBrainz's alone, and Blues 0.5, which Last.fm's 0.2 lifts to 0.7.
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to lastFm("rock", "pop", "folk", "jazz", "blues"),
                    LabelSource.MUSICBRAINZ to musicBrainz("soul", "blues"),
                ),
            )
        assertEquals(listOf("Rock", "Pop", "Blues", "Folk", "Soul"), combined.map { it.genre.label })
        assertEquals(
            listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
            combined.single { it.genre.label == "Blues" }.credits.map { it.source },
        )
    }

    @Test
    fun `counts weigh each tag against its source's top tag, and agreement lifts a genre`() {
        // Last.fm: Indie Rock 1, Indie 0.6; MusicBrainz: Pop Rock 1 (20 of 20 votes), Indie 0.5.
        // Indie, given by both, weighs 1.1; at equal weight Last.fm's comes first.
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.MUSICBRAINZ to counted(MB_PAGE, "pop rock" to 20, "indie" to 10),
                    LabelSource.LASTFM to counted(LASTFM_PAGE, "indie rock" to 100, "indie" to 60),
                ),
            )
        assertEquals(listOf("Indie", "Indie Rock", "Pop Rock"), combined.map { it.genre.label })
    }

    @Test
    fun `weights equal but for floating-point rounding are a tie, kept in the sources' order`() {
        // Rock: Last.fm's 0.1 and MusicBrainz's two votes of ten, 0.2, sum to 0.30000000000000004;
        // Pop's 0.3 is equal, and Last.fm's Pop, given first, keeps the fifth place.
        val recording =
            MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
                .recordingLabels(RecordingTags("mb-rec", listOf("rock"), mapOf("rock" to 2)))
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to
                        counted(
                            LASTFM_PAGE,
                            "jazz" to 100,
                            "soul" to 90,
                            "folk" to 80,
                            "funk" to 70,
                            "pop" to 30,
                            "rock" to 10,
                        ),
                    LabelSource.MUSICBRAINZ to recording,
                ),
            )
        assertEquals(listOf("Jazz", "Soul", "Folk", "Funk", "Pop"), combined.map { it.genre.label })
    }

    @Test
    fun `a MusicBrainz tag of fewer votes than its full count weighs its share of them`() {
        // A recording's one-vote tags weigh 0.1 each, under Last.fm's tags of 40 and up.
        val recording =
            MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
                .recordingLabels(RecordingTags("mb-rec", listOf("rock", "pop"), mapOf("rock" to 1, "pop" to 1)))
        assertEquals(listOf(0.1, 0.1), recording.map { it.weight })
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.MUSICBRAINZ to recording,
                    LabelSource.LASTFM to
                        counted(LASTFM_PAGE, "post-punk" to 100, "new wave" to 60, "gothic rock" to 40),
                ),
            )
        assertEquals(listOf("Post-Punk", "New Wave", "Gothic Rock", "Rock", "Pop"), combined.map { it.genre.label })
    }

    @Test
    fun `a genre only MusicBrainz gives counts at half its weight, a shared one at its full sum`() {
        // As Coldplay's artist row: MusicBrainz's Pop, 42 of 100 votes, weighs 0.42 but counts at
        // 0.21, under Last.fm's Indie at 0.3; Rock, given by both, keeps MusicBrainz's full 1.
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to
                        counted(
                            LASTFM_PAGE,
                            "rock" to 100,
                            "alternative" to 90,
                            "britpop" to 80,
                            "alternative rock" to 70,
                            "indie" to 30,
                        ),
                    LabelSource.MUSICBRAINZ to counted(MB_PAGE, "rock" to 100, "pop" to 42),
                ),
            )
        assertEquals(
            listOf("Rock", "Alternative", "Britpop", "Alternative Rock", "Indie"),
            combined.map { it.genre.label },
        )
    }

    @Test
    fun `a recording's two-vote tag only MusicBrainz gives leaves Last fm's fifth in place`() {
        // As "Spellbound": MusicBrainz's Rock, 2 votes of `FULL_WEIGHT_VOTES` (10), weighs 0.2 and
        // counts at 0.1, under Last.fm's Goth at 0.15.
        val recording =
            MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
                .recordingLabels(RecordingTags("mb-rec", listOf("rock"), mapOf("rock" to 2)))
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to
                        counted(
                            LASTFM_PAGE,
                            "post-punk" to 100,
                            "new wave" to 60,
                            "gothic" to 40,
                            "gothic rock" to 20,
                            "goth" to 15,
                        ),
                    LabelSource.MUSICBRAINZ to recording,
                ),
            )
        assertEquals(
            listOf("Post-Punk", "New Wave", "Gothic", "Gothic Rock", "Goth"),
            combined.map { it.genre.label },
        )
    }

    @Test
    fun `a source's sixth tag that another source agrees on takes a place on the row`() {
        // Before #1019 each source's labels were capped at five in its own order, so Last.fm's
        // sixth, Blues, never reached the row whatever MusicBrainz said.
        val combined =
            combineLabels(
                mapOf(
                    LabelSource.LASTFM to
                        counted(
                            LASTFM_PAGE,
                            "rock" to 100,
                            "pop" to 90,
                            "folk" to 80,
                            "jazz" to 70,
                            "soul" to 60,
                            "blues" to 50,
                        ),
                    LabelSource.MUSICBRAINZ to counted(MB_PAGE, "blues" to 4),
                ),
            )
        assertEquals(listOf("Blues", "Rock", "Pop", "Folk", "Jazz"), combined.map { it.genre.label })
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
