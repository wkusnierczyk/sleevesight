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
        assertEquals(emptyList(), none.chips())
        assertEquals(null, none.opening())
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
    fun `the AI's genres no row has join the track's row, the others credited unmarked (#762)`() {
        val listed = track("rock", "indie folk", "blues")
        val model = "Claude Haiku 5.5"
        val genres = blackOak().withAi(listed, model)
        val row = genres.rows.first()
        assertEquals(GenreLevel.TRACK, row.level)
        // Rock, on the track's row, credited to the model too; Indie Folk, on the artist's, not added again.
        assertEquals(listOf("Alternative Rock", "Rock", "Blues"), row.labels.map { it.genre.label })
        assertEquals(listOf(false, false, true), row.labels.map { it.aiAdded })
        assertEquals(listOf(null, model, model), row.labels.map { it.aiModel })
        assertTrue(row.labels.filter { it.aiAdded }.all { it.credits.isEmpty() })
        val artist = genres.rows.single { it.level == GenreLevel.ARTIST }
        assertEquals(listOf(null, model), artist.labels.map { it.aiModel })
        assertTrue(artist.labels.none { it.aiAdded })
        assertEquals(genres, genres.withAi(listed, model))
        assertEquals(blackOak(), blackOak().withAi(emptyList(), model))
    }

    @Test
    fun `a listed genre a row has gets no copy in the rows, and no chip of its own (#762, #875)`() {
        val model = "Claude Haiku 5.5"
        // Folk is the album's (and the artist's), and a source's chip: no second chip, no copy in the rows.
        val folkChip = blackOak().withAi(track("folk", "indie folk", "blues"), model)
        assertEquals(
            listOf("Alternative Rock", "Rock", "Blues"),
            folkChip.rows
                .first()
                .labels
                .map { it.genre.label },
        )
        assertEquals(
            listOf("Alternative Rock", "Folk", "Indie Folk", "Rock", "Blues"),
            folkChip.chips().map { it.label.genre.label },
        )
        assertEquals(listOf(false, false, false, false, true), folkChip.chips().map { it.label.aiAdded })
        // Still listed, so it may reach a prompt.
        assertEquals(
            model,
            folkChip.rows
                .single { it.level == GenreLevel.ALBUM }
                .labels
                .single()
                .aiModel,
        )
        // The album's Folk is a chip beside a backed track tag (#875, where #799's fold left it out):
        // the list's Folk is no chip of its own, unmarked, and still not on the track's row.
        val rockVotes = vocabulary.labelsFor(listOf("rock"), level = GenreLevel.TRACK, votes = mapOf("rock" to 3))
        val backed =
            combineGenres(
                mapOf(LabelSource.MUSICBRAINZ to rockVotes, LabelSource.LASTFM to album("folk")),
                emptyList(),
            )
        assertEquals(listOf("Folk", "Rock"), backed.chips().map { it.label.genre.label })
        val listed = backed.withAi(track("folk"), model)
        assertEquals(listOf("Folk", "Rock"), listed.chips().map { it.label.genre.label })
        assertEquals(listOf(false, false), listed.chips().map { it.label.aiAdded })
        assertEquals(
            listOf("Rock"),
            listed.rows
                .first()
                .labels
                .map { it.genre.label },
        )
        assertEquals(listed, listed.withAi(track("folk"), model))
    }

    @Test
    fun `the AI's genres follow the sources' chips, which stay as they were (#762, #875)`() {
        val sources = listOf("Alternative Rock", "Folk", "Indie Folk", "Rock")
        assertEquals(sources, blackOak().chips().map { it.label.genre.label })
        // Blues added by the AI backs nothing: the sources' chips stay, the weak ones weak.
        val genres = blackOak().withAi(track("blues", "folk"), "Claude Haiku 5.5")
        assertEquals(sources + "Blues", genres.chips().map { it.label.genre.label })
        assertEquals(blackOak().chips().map { it.weak }, genres.chips().take(sources.size).map { it.weak })
        // With no track row before: one made for the AI's genres alone.
        val albumOnly = combineGenres(mapOf(LabelSource.LASTFM to album("folk")), emptyList())
        val made = albumOnly.withAi(track("blues"), "Claude Haiku 5.5")
        assertEquals(listOf(GenreLevel.TRACK, GenreLevel.ALBUM), made.levels)
        assertEquals(listOf("Folk", "Blues"), made.chips().map { it.label.genre.label })
    }

    @Test
    fun `the AI's genres fill the track's row up to the cap, and a genre left out shows nowhere (#762)`() {
        val four = combineGenres(mapOf(LabelSource.LASTFM to track("rock", "pop", "punk", "soul")), emptyList())
        val genres = four.withAi(track("blues", "jazz"), "Claude Haiku 5.5")
        val row = genres.rows.single()
        assertEquals(GenreVocabulary.MAX_LABELS, row.labels.size)
        assertEquals(listOf("Rock", "Pop", "Punk", "Soul", "Blues"), row.labels.map { it.genre.label })
        // The chips the same, the sources' in alphabetical order: Jazz, on no row, has no chip either.
        assertEquals(listOf("Pop", "Punk", "Rock", "Soul", "Blues"), genres.chips().map { it.label.genre.label })
        assertEquals(genres, genres.withAi(track("blues", "jazz"), "Claude Haiku 5.5"))
    }
}
