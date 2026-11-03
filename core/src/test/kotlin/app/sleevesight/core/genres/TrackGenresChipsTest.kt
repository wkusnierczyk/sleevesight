package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * *Genres*' chips (#875, replacing #799's fold): every genre of the rows, each once, the sources'
 * alphabetical and then the AI's; weak track tags kept and marked, with why; the genre the panel
 * opens on.
 */
class TrackGenresChipsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK)

    private fun album(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ALBUM)

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    // The recording's MusicBrainz labels, with each tag's votes (#799), as [MusicBrainzLabels] gives them.
    private fun recording(vararg voted: Pair<String, Int>) =
        MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
            .recordingLabels(RecordingTags("mb-rec", voted.map { it.first }, voted.toMap()))

    private fun genres(
        musicBrainz: List<GenreLabel> = emptyList(),
        lastFm: List<GenreLabel> = emptyList(),
        artists: List<String> = listOf("A", "B"),
    ) = combineGenres(mapOf(LabelSource.MUSICBRAINZ to musicBrainz, LabelSource.LASTFM to lastFm), artists)

    // Each chip's name, and whether it is weak.
    private fun TrackGenres.shown() = chips().map { it.label.genre.label to it.weak }

    // The case reported in #875: a one-vote MusicBrainz Country on the track, and the artist's
    // Last.fm Acoustic, Folk and Indie Folk.
    private fun reported() = genres(recording("country" to 1), artist("A", "acoustic", "folk", "indie folk"))

    @Test
    fun `a one-vote track tag no other level has is a chip, marked weak, where the fold left it out`() {
        assertEquals(
            listOf("Acoustic" to false, "Country" to true, "Folk" to false, "Indie Folk" to false),
            reported().shown(),
        )
        assertEquals(GenreLevel.TRACK, reported().chips()[1].row.level)
    }

    @Test
    fun `a track tag is backed by its votes, or by an artist's or the album's row having its genre`() {
        val voted = recording("soft rock" to 2, "jazz" to 1, "folk" to 1, "metal" to 1)
        val genres = genres(voted, album("jazz") + artist("B", "folk"))
        assertEquals(
            listOf("Folk" to false, "Jazz" to false, "Metal" to true, "Soft Rock" to false),
            genres.shown(),
        )
        // The genre the album shares is placed on the track's row, unmarked.
        assertEquals(
            GenreLevel.TRACK,
            genres
                .chips()
                .single { it.label.genre.label == "Jazz" }
                .row.level,
        )
    }

    @Test
    fun `a tag only Last_fm gives the track is weak, its weights being no votes, unless another level has it`() {
        val genres = genres(lastFm = track("soul", "funk") + album("funk"))
        assertEquals(listOf("Funk" to false, "Soul" to true), genres.shown())
    }

    @Test
    fun `with only the track tagged, its tags are weak but for one with enough votes`() {
        assertEquals(
            listOf("Jazz" to true, "Soft Rock" to false),
            genres(recording("soft rock" to TrackGenres.MIN_VOTES, "jazz" to 1)).shown(),
        )
        assertEquals(listOf("Rock" to true), genres(lastFm = track("rock")).shown())
    }

    @Test
    fun `every row's genres are chips, each once, with no cap beyond the rows'`() {
        val genres =
            genres(
                lastFm =
                    track("rock", "pop") + artist("A", "rock", "jazz", "blues") + artist("B", "soul", "funk") +
                        album("pop", "metal", "reggae"),
            )
        val names = genres.chips().map { it.label.genre.label }
        assertEquals(listOf("Blues", "Funk", "Jazz", "Metal", "Pop", "Reggae", "Rock", "Soul"), names)
        assertEquals(
            genres.all.map { it.genre.identity }.toSet(),
            genres.chips().map { it.label.genre.identity }.toSet(),
        )
    }

    @Test
    fun `the sources' chips are alphabetical whatever their case and level, the AI's after, alphabetical`() {
        // Written for the test: a name in lower case sorts among the others, as a reader expects.
        val bebop = GenreLabel("bebop", "jazz", "bebop", "bebop", level = GenreLevel.TRACK, votes = 3)
        val genres =
            combineGenres(
                mapOf(LabelSource.LASTFM to album("soul") + artist("A", "blues") + listOf(bebop)),
                listOf("A"),
            ).withAi(track("soul", "techno", "funk"), "Claude Haiku 5.5")
        assertEquals(
            listOf("bebop", "Blues", "Soul", "Funk", "Techno"),
            genres.chips().map { it.label.genre.label },
        )
        assertEquals(listOf(false, false, false, true, true), genres.chips().map { it.label.aiAdded })
        // Soul is on the AI's list and a source's: a source's chip, unmarked, credited to the model.
        assertEquals("Claude Haiku 5.5", genres.chips()[2].label.aiModel)
    }

    @Test
    fun `the panel opens on the first backed genre, else the first weak one, never the AI's, else none`() {
        assertEquals(
            "Acoustic",
            reported()
                .opening()
                ?.label
                ?.genre
                ?.label,
        )
        // Every source's chip weak: the first of them, not an AI's that sorts before it.
        val weak = genres(lastFm = track("soul", "rock")).withAi(track("blues"), "Claude Haiku 5.5")
        assertEquals(listOf("Rock", "Soul", "Blues"), weak.chips().map { it.label.genre.label })
        assertEquals(
            "Rock",
            weak
                .opening()
                ?.label
                ?.genre
                ?.label,
        )
        // A backed genre after weak ones in the order.
        assertEquals(
            "Soul",
            genres(recording("jazz" to 1, "soul" to 2))
                .opening()
                ?.label
                ?.genre
                ?.label,
        )
        assertNull(TrackGenres().opening())
    }

    @Test
    fun `why a weak genre is weak names its source and the levels without it`() {
        val mb = WeakEvidence(musicBrainz = true, artists = 1, album = false)
        assertEquals(mb, reported().weakEvidence("country"))
        // Backed, or no chip: nothing to say.
        assertNull(reported().weakEvidence("folk"))
        assertNull(reported().weakEvidence("jazz"))
        // Last.fm alone, two artists and the album.
        val lastFm = genres(lastFm = track("soul") + artist("A", "rock") + artist("B", "pop") + album("jazz"))
        assertEquals(WeakEvidence(musicBrainz = false, artists = 2, album = true), lastFm.weakEvidence("soul"))
        // The album alone; then no other level.
        assertEquals(
            WeakEvidence(musicBrainz = true, artists = 0, album = true),
            genres(recording("soul" to 1), album("jazz")).weakEvidence("soul"),
        )
        assertEquals(
            WeakEvidence(musicBrainz = false, artists = 0, album = false),
            genres(lastFm = track("soul")).weakEvidence("soul"),
        )
        // Given by both sources, one vote: MusicBrainz's contributors tagged it.
        val both = genres(recording("soul" to 1), track("soul"))
        assertEquals(WeakEvidence(musicBrainz = true, artists = 0, album = false), both.weakEvidence("soul"))
    }
}
