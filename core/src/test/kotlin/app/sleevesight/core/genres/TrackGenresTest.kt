package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A track's genres as rows (#439): the levels, their order, rows with none left out, one level, none. */
class TrackGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK)

    private fun album(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ALBUM)

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    private fun TrackGenres.shown() =
        rows.map { row -> Triple(row.level, row.artist, row.labels.map { it.genre.label }) }

    @Test
    fun `a row per level, in the header's order, the track, each artist in credit order, the album`() {
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.LASTFM to
                        album("folk rock") + artist("Ian Tyson", "country") + artist("Ian", "folk") +
                        track("soft rock", "pop"),
                ),
                listOf("Ian", "Ian Tyson"),
            )
        assertEquals(
            listOf(
                Triple(GenreLevel.TRACK, null, listOf("Soft Rock", "Pop")),
                Triple(GenreLevel.ARTIST, "Ian", listOf("Folk")),
                Triple(GenreLevel.ARTIST, "Ian Tyson", listOf("Country")),
                Triple(GenreLevel.ALBUM, null, listOf("Folk Rock")),
            ),
            genres.shown(),
        )
        assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ARTIST, GenreLevel.ALBUM), genres.levels)
        assertFalse(genres.oneLevel)
    }

    @Test
    fun `a row combines its sources' labels, crediting each, a genre on two levels shows on each`() {
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to track("rock", "folk") + artist("A", "rock"),
                    LabelSource.LASTFM to track("rock") + album("rock"),
                ),
                listOf("A"),
            )
        assertEquals(
            listOf(
                Triple(GenreLevel.TRACK, null, listOf("Rock", "Folk")),
                Triple(GenreLevel.ARTIST, "A", listOf("Rock")),
                Triple(GenreLevel.ALBUM, null, listOf("Rock")),
            ),
            genres.shown(),
        )
        assertEquals(
            listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
            genres.rows
                .first()
                .labels
                .first()
                .credits
                .map { it.source },
        )
    }

    @Test
    fun `at most five labels a row`() {
        val many = arrayOf("rock", "pop", "folk", "jazz", "blues", "soul", "funk")
        val genres = combineGenres(mapOf(LabelSource.LASTFM to track(*many) + album(*many)), emptyList())
        assertTrue(genres.rows.all { it.labels.size == GenreVocabulary.MAX_LABELS })
    }

    // The recording's MusicBrainz labels, with each tag's votes (#799), as [MusicBrainzLabels] gives them.
    private fun recording(vararg voted: Pair<String, Int>) =
        MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
            .recordingLabels(RecordingTags("mb-rec", voted.map { it.first }, voted.toMap()))

    private fun TrackGenres.foldedShown() = folded.map { it.row.level to it.label.genre.label }

    @Test
    fun `folded, with no track label, the album's show, else the artists' together`() {
        val artists = artist("A", "rock", "pop") + artist("B", "pop", "jazz")

        fun folded(vararg labels: List<GenreLabel>) =
            combineGenres(mapOf(LabelSource.LASTFM to labels.toList().flatten()), listOf("A", "B")).foldedShown()

        assertEquals(listOf(GenreLevel.ALBUM to "Folk"), folded(artists, album("folk")))
        assertEquals(
            listOf(GenreLevel.ARTIST to "Rock", GenreLevel.ARTIST to "Pop", GenreLevel.ARTIST to "Jazz"),
            folded(artists),
        )
    }

    @Test
    fun `folded, one-vote track tags that agree with nothing give way to the album's, the rows keep them`() {
        // The case of #799, with tags written for the test: a recording with four one-vote tags that
        // agree with neither the artist's labels nor the album's.
        val genres =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to
                        recording("jazz" to 1, "techno" to 1, "reggae" to 1, "metal" to 1) +
                        artist("A", "folk", "blues"),
                    LabelSource.LASTFM to artist("A", "folk", "singer-songwriter") + album("folk"),
                ),
                listOf("A"),
            )
        assertEquals(listOf(GenreLevel.ALBUM to "Folk"), genres.foldedShown())
        // The *Genre tags* table still shows every level's.
        assertEquals(
            listOf("Jazz", "Techno", "Reggae", "Metal"),
            genres.rows
                .first { it.level == GenreLevel.TRACK }
                .labels
                .map { it.genre.label },
        )
    }

    @Test
    fun `folded, a track tag with two votes leads, and a one-vote one only when another level has it`() {
        val artists = artist("A", "folk")

        fun folded(vararg labels: List<GenreLabel>) =
            combineGenres(mapOf(LabelSource.MUSICBRAINZ to labels.toList().flatten()), listOf("A")).foldedShown()

        // Well voted: it leads, the one-vote tag beside it that agrees with nothing does not.
        assertEquals(
            listOf(GenreLevel.TRACK to "Soft Rock"),
            folded(recording("soft rock" to 2, "pop" to 1), album("folk"), artists),
        )
        // One vote, but the album has it: it leads.
        assertEquals(
            listOf(GenreLevel.TRACK to "Folk"),
            folded(recording("rock" to 1, "folk" to 1), album("folk", "country"), artists),
        )
        // One vote, an artist has it.
        assertEquals(listOf(GenreLevel.TRACK to "Folk"), folded(recording("folk" to 1), album("country"), artists))
        // With no other level, the track's show all the same.
        assertEquals(listOf(GenreLevel.TRACK to "Rock"), folded(recording("rock" to 1)))
    }

    @Test
    fun `folded, another level backs a one-vote track tag by genre, whatever its spelling, and any artist's row`() {
        fun folded(vararg labels: List<GenreLabel>) =
            combineGenres(mapOf(LabelSource.MUSICBRAINZ to labels.toList().flatten()), listOf("A", "B")).foldedShown()

        // `hip-hop` and `hip hop` are one genre, as `r&b` and `rnb` are.
        assertEquals(
            listOf(GenreLevel.TRACK to "Hip-Hop"),
            folded(recording("hip-hop" to 1, "metal" to 1), album("hip hop")),
        )
        assertEquals(listOf(GenreLevel.TRACK to "R&B"), folded(recording("r&b" to 1, "metal" to 1), album("rnb")))
        // Only the second credited artist's row has it.
        assertEquals(
            listOf(GenreLevel.TRACK to "Jazz"),
            folded(recording("jazz" to 1, "metal" to 1), artist("A", "folk"), artist("B", "jazz")),
        )
    }

    @Test
    fun `folded, a Last_fm track tag leads only when another level has it, its weights being no votes`() {
        fun folded(vararg labels: List<GenreLabel>) =
            combineGenres(mapOf(LabelSource.LASTFM to labels.toList().flatten()), listOf("A")).foldedShown()

        assertEquals(listOf(GenreLevel.ALBUM to "Folk"), folded(track("soft rock"), album("folk")))
        assertEquals(listOf(GenreLevel.TRACK to "Folk"), folded(track("soft rock", "folk"), album("folk")))
        assertEquals(listOf(GenreLevel.TRACK to "Rock"), folded(track("rock"), artist("A", "rock")))
    }

    @Test
    fun `a track label's votes are its sources' most, kept when Last_fm gives it first`() {
        val genres =
            combineGenres(
                mapOf(LabelSource.MUSICBRAINZ to recording("soft rock" to 3), LabelSource.LASTFM to track("soft rock")),
                emptyList(),
            )
        val label =
            genres.rows
                .single()
                .labels
                .single()
        assertEquals(listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ), label.credits.map { it.source })
        assertEquals(3, label.votes)
        val lastFm = combineGenres(mapOf(LabelSource.LASTFM to track("soft rock")), emptyList())
        assertEquals(null, lastFm.all.single().votes)
    }

    @Test
    fun `labels of one level only, and none`() {
        val one =
            combineGenres(mapOf(LabelSource.LASTFM to artist("A", "rock") + artist("B", "jazz")), listOf("A", "B"))
        assertTrue(one.oneLevel)
        assertEquals(2, one.rows.size)

        val none = combineGenres(mapOf(LabelSource.LASTFM to track("seen live")), listOf("A"))
        assertTrue(none.isEmpty)
        assertEquals(emptyList(), none.folded)
    }

    @Test
    fun `an artist's label opens a genre card as before, the track's and the album's do not`() {
        val genres =
            combineGenres(mapOf(LabelSource.LASTFM to track("rock") + artist("A", "rock") + album("rock")), listOf("A"))
        assertEquals(listOf(false, true, false), genres.rows.map { it.labels.single().opensCard })
    }

    // The Black Oak (#762, after #799): one-vote MusicBrainz track tags, and Last.fm's Folk for the
    // album and the artist.
    private fun blackOak(): TrackGenres =
        combineGenres(
            mapOf(
                LabelSource.MUSICBRAINZ to track("alternative rock", "rock"),
                LabelSource.LASTFM to album("folk") + artist("Emily Jane White", "folk", "indie folk"),
            ),
            listOf("Emily Jane White"),
        )

    @Test
    fun `the AI's genres join the track's row, those it has already unmarked, and applied twice the same (#762)`() {
        val listed = track("rock", "indie folk", "blues")
        val genres = blackOak().withAi(listed, "Claude Haiku 5.5")
        val row = genres.rows.first()
        assertEquals(GenreLevel.TRACK, row.level)
        assertEquals(listOf("Alternative Rock", "Rock", "Indie Folk", "Blues"), row.labels.map { it.genre.label })
        // Rock, which MusicBrainz gives, is credited to the model too, unmarked; the others are added.
        assertEquals(listOf(false, false, true, true), row.labels.map { it.aiAdded })
        assertEquals(
            listOf(null, "Claude Haiku 5.5", "Claude Haiku 5.5", "Claude Haiku 5.5"),
            row.labels.map { it.aiModel },
        )
        assertTrue(row.labels.filter { it.aiAdded }.all { it.credits.isEmpty() })
        // The other rows as they were; twice, the same.
        assertEquals(blackOak().rows.drop(1), genres.rows.drop(1))
        assertEquals(genres, genres.withAi(listed, "Claude Haiku 5.5"))
        assertEquals(blackOak(), blackOak().withAi(emptyList(), "Claude Haiku 5.5"))
    }

    @Test
    fun `the AI's genres follow the folded chips, which stay as they were, each genre once (#762)`() {
        val before = blackOak().folded.map { it.label.genre.label }
        assertEquals(listOf("Folk"), before)
        // Indie Folk is on the artist's row, but added by the AI it backs nothing: the album's Folk still leads.
        val genres = blackOak().withAi(track("folk", "indie folk", "blues"), "Claude Haiku 5.5")
        assertEquals(listOf("Folk", "Indie Folk", "Blues"), genres.folded.map { it.label.genre.label })
        assertEquals(listOf(false, true, true), genres.folded.map { it.label.aiAdded })
        // With no track row before: one made for the AI's genres alone.
        val albumOnly = combineGenres(mapOf(LabelSource.LASTFM to album("folk")), emptyList())
        val made = albumOnly.withAi(track("blues"), "Claude Haiku 5.5")
        assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ALBUM), made.levels)
        assertEquals(listOf("Folk", "Blues"), made.folded.map { it.label.genre.label })
    }
}
