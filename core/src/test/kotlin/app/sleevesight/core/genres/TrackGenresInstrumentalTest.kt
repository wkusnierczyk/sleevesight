package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumental weighed against the signs that the track is sung (#996): lyrics found drop it from
 * every row; a language of the work, a vocal credit or a featured artist leave it at most weak;
 * the recording marked instrumental leaves it as tagged; every other genre as before.
 */
class TrackGenresInstrumentalTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private fun track(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.TRACK)

    private fun album(vararg tags: String) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ALBUM)

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    // The recording's MusicBrainz labels, with each tag's votes, as [MusicBrainzLabels] gives them.
    private fun recording(vararg voted: Pair<String, Int>) =
        MusicBrainzLabels(ArtistTags { error("no artist asked") }, vocabulary)
            .recordingLabels(RecordingTags("mb-rec", voted.map { it.first }, voted.toMap()))

    private fun genres(
        lastFm: List<GenreLabel>,
        musicBrainz: List<GenreLabel> = emptyList(),
        artists: List<String> = listOf(ARTIST),
        singing: Singing = Singing.NONE,
    ) = combineGenres(mapOf(LabelSource.MUSICBRAINZ to musicBrainz, LabelSource.LASTFM to lastFm), artists, singing)

    // Every level's tags of Instrumental, with Jazz beside it on each.
    private fun everyLevel() =
        track("instrumental", "jazz") + artist(ARTIST, "instrumental", "jazz") + album("instrumental", "jazz")

    // Each row's genres, by level.
    private fun TrackGenres.byRow() = rows.map { row -> row.level to row.labels.map { it.genre.identity } }

    // Each chip's genre, and whether it is weak.
    private fun TrackGenres.shown() = chips().associate { it.label.genre.identity to it.weak }

    @Test
    fun `lyrics found drop Instrumental from every row, the track's own included, and keep the rest`() {
        val sung = genres(everyLevel(), singing = Singing(lyrics = true))
        assertEquals(
            listOf(
                GenreLevel.TRACK to listOf("jazz"),
                GenreLevel.ARTIST to listOf("jazz"),
                GenreLevel.ALBUM to listOf("jazz"),
            ),
            sung.byRow(),
        )
        assertEquals(mapOf("jazz" to false), sung.shown())
        // A row of Instrumental alone goes with it; left out before the sources' labels combine, a
        // full row has room for the other source's.
        assertTrue(genres(artist(ARTIST, "instrumental"), singing = Singing(lyrics = true)).isEmpty)
        val rock = recording("rock" to 1).map { it.copy(level = GenreLevel.ARTIST, artist = ARTIST) }
        val five = artist(ARTIST, "instrumental", "jazz", "blues", "soul", "funk")
        val full = genres(five, rock, singing = Singing(lyrics = true))
        assertEquals(
            listOf("jazz", "blues", "soul", "funk", "rock"),
            full.rows
                .single()
                .labels
                .map { it.genre.identity },
        )
    }

    @Test
    fun `lyrics found keep the AI's Instrumental off the track's row, and Find the lyrics drops it too`() {
        val listed = vocabulary.labelsFor(listOf("instrumental", "blues"), level = GenreLevel.TRACK)
        val sung = genres(artist(ARTIST, "jazz"), singing = Singing(lyrics = true)).withAi(listed, "Model")
        assertEquals(
            listOf("blues"),
            sung.rows
                .first { it.level == GenreLevel.TRACK }
                .labels
                .map { it.genre.identity },
        )
        // Lyrics a tap on *Find the lyrics* found, after the AI's list.
        val found = genres(everyLevel()).withAi(listed, "Model").withLyricsFound()
        assertTrue(found.all.none { it.genre.identity == TrackGenres.INSTRUMENTAL })
        assertEquals(listOf("jazz", "blues"), found.all.map { it.genre.identity })
        assertTrue(found.singing.lyrics)
    }

    @Test
    fun `a language given for the work leaves Instrumental weak on the artist's row, named`() {
        val japanese = genres(artist(ARTIST, "instrumental", "jazz"), singing = Singing(languages = listOf("jpn")))
        assertEquals(mapOf("instrumental" to true, "jazz" to false), japanese.shown())
        assertEquals(WeakEvidence.Language("jpn", "Japanese"), japanese.weakEvidence("instrumental"))
        assertNull(japanese.weakEvidence("jazz"))
        // On the album's row, and the track's with two votes, the same; a language Sleevesight does
        // not name is given by its code alone.
        val other = Singing(languages = listOf("mul"))
        assertEquals(
            WeakEvidence.Language("mul", null),
            genres(album("instrumental"), singing = other).weakEvidence("instrumental"),
        )
        val voted = genres(emptyList(), recording("instrumental" to 3), singing = other)
        assertEquals(mapOf("instrumental" to true), voted.shown())
    }

    @Test
    fun `a vocal credit leaves Instrumental weak, naming the vocalist before the language`() {
        val singing = Singing(languages = listOf("jpn"), vocalists = listOf("Yurika Hanashima"))
        val credited = genres(artist(ARTIST, "instrumental") + album("instrumental", "jazz"), singing = singing)
        assertEquals(mapOf("instrumental" to true, "jazz" to false), credited.shown())
        assertEquals(WeakEvidence.Vocalist("Yurika Hanashima"), credited.weakEvidence("instrumental"))
        // The panel opens on the backed genre.
        assertEquals(
            "jazz",
            credited
                .opening()
                ?.label
                ?.genre
                ?.identity,
        )
    }

    @Test
    fun `a featured artist with no Instrumental of their own leaves another artist's Instrumental weak`() {
        // *Nasnusa*: Sababa 5 tagged instrumental, Yurika Hanashima with no tags, no MusicBrainz work.
        val nasnusa = genres(artist(ARTIST, "instrumental", "funk"), artists = listOf(ARTIST, FEATURED))
        assertEquals(
            GenreLevel.ARTIST,
            nasnusa
                .chips()
                .first { it.label.genre.identity == "instrumental" }
                .row.level,
        )
        assertEquals(mapOf("funk" to false, "instrumental" to true), nasnusa.shown())
        assertEquals(WeakEvidence.Featured(FEATURED), nasnusa.weakEvidence("instrumental"))
        // The album's Instrumental too.
        val albums = genres(album("instrumental"), artists = listOf(ARTIST, FEATURED))
        assertEquals(WeakEvidence.Featured(ARTIST), albums.weakEvidence("instrumental"))
        // Both artists tagged instrumental, one artist credited, or the track's own tag: unchanged.
        val both =
            genres(
                artist(ARTIST, "instrumental") + artist(FEATURED, "instrumental"),
                artists = listOf(ARTIST, FEATURED),
            )
        assertEquals(mapOf("instrumental" to false), both.shown())
        assertEquals(mapOf("instrumental" to false), genres(artist(ARTIST, "instrumental")).shown())
        val ownTag =
            genres(artist(ARTIST, "instrumental"), recording("instrumental" to 2), artists = listOf(ARTIST, FEATURED))
        assertEquals(mapOf("instrumental" to false), ownTag.shown())
    }

    @Test
    fun `a featured artist's Instrumental beyond their row's cap still counts as theirs`() {
        // B's Last.fm tags fill B's row; MusicBrainz's Instrumental for B is left out of it.
        val lastFm = artist(ARTIST, "instrumental") + artist(FEATURED, "jazz", "blues", "soul", "funk", "rock")
        val both = genres(lastFm, artist(FEATURED, "instrumental"), artists = listOf(ARTIST, FEATURED))
        assertTrue(
            both.rows
                .single { it.artist == FEATURED }
                .labels
                .none { it.genre.identity == "instrumental" },
        )
        assertEquals(false, both.shown()["instrumental"])
        assertNull(both.weakEvidence("instrumental"))
    }

    @Test
    fun `lyrics Find the lyrics found combine the labels again, so another takes Instrumental's room`() {
        // The track's row full with Instrumental; MusicBrainz's Rock and the AI's Blues left out.
        val full = genres(track("instrumental", "jazz", "soul", "funk", "country"), recording("rock" to 2))
        val listed = vocabulary.labelsFor(listOf("blues"), level = GenreLevel.TRACK)
        val shown = full.withAi(listed, "Model")
        assertEquals(
            5,
            shown.rows
                .single()
                .labels.size,
        )
        val found = shown.withLyricsFound()
        assertEquals(
            listOf("jazz", "soul", "funk", "country", "rock"),
            found.rows
                .single()
                .labels
                .map { it.genre.identity },
        )
        // With the AI's list after the sources' labels, as before; applied twice, the same.
        assertEquals(found, found.withLyricsFound())
        val room = genres(track("instrumental", "jazz"), singing = Singing()).withAi(listed, "Model").withLyricsFound()
        assertEquals(
            listOf("jazz", "blues"),
            room.rows
                .single()
                .labels
                .map { it.genre.identity },
        )
        assertEquals("Model", room.aiModel)
    }

    @Test
    fun `a track's Instrumental of few votes backed only by rows the featured rule weakens is weak too`() {
        val artists = listOf(ARTIST, FEATURED)
        // *Nasnusa* with a Last.fm track tag of Instrumental, or a one-vote MusicBrainz one.
        val lastFm = genres(track("instrumental") + artist(ARTIST, "instrumental", "funk"), artists = artists)
        assertEquals(
            GenreLevel.TRACK,
            lastFm
                .chips()
                .first { it.label.genre.identity == "instrumental" }
                .row.level,
        )
        assertEquals(WeakEvidence.Featured(FEATURED), lastFm.weakEvidence("instrumental"))
        val oneVote = genres(artist(ARTIST, "instrumental"), recording("instrumental" to 1), artists = artists)
        assertEquals(WeakEvidence.Featured(FEATURED), oneVote.weakEvidence("instrumental"))
        // Two votes stand on their own; a backing row that is not weak backs it; one artist, no rule.
        val twoVotes = genres(artist(ARTIST, "instrumental"), recording("instrumental" to 2), artists = artists)
        assertNull(twoVotes.weakEvidence("instrumental"))
        val backed =
            genres(
                track("instrumental") + artist(ARTIST, "instrumental") + artist(FEATURED, "instrumental"),
                artists = artists,
            )
        assertNull(backed.weakEvidence("instrumental"))
        assertNull(genres(track("instrumental") + artist(ARTIST, "instrumental")).weakEvidence("instrumental"))
    }

    @Test
    fun `an artist's Instrumental past their own five tags still counts as theirs`() {
        // One source: B's sixth genre is Instrumental, kept past the cap as the sources keep it.
        val sixth =
            vocabulary.labelsFor(
                listOf("jazz", "blues", "soul", "funk", "rock", "instrumental"),
                level = GenreLevel.ARTIST,
                artist = FEATURED,
                keepBeyondCap = setOf(TrackGenres.INSTRUMENTAL),
            )
        assertEquals(listOf(false, false, false, false, false, true), sixth.map { it.beyondCap })
        val one = genres(artist(ARTIST, "instrumental") + sixth, artists = listOf(ARTIST, FEATURED))
        // On no row, but B's tags include it: A's Instrumental is not weak.
        assertEquals(
            5,
            one.rows
                .single { it.artist == FEATURED }
                .labels.size,
        )
        assertTrue(one.all.none { it.genre.beyondCap })
        assertNull(one.weakEvidence("instrumental"))
        // Without it kept, B is taken for a featured artist without it.
        val capped = artist(FEATURED, "jazz", "blues", "soul", "funk", "rock", "instrumental")
        val without = genres(artist(ARTIST, "instrumental") + capped, artists = listOf(ARTIST, FEATURED))
        assertEquals(WeakEvidence.Featured(FEATURED), without.weakEvidence("instrumental"))
    }

    @Test
    fun `the AI's Instrumental is weak with a vocalist or a language, and its other genres as before`() {
        val listed = vocabulary.labelsFor(listOf("instrumental", "blues"), level = GenreLevel.TRACK)
        val japanese =
            genres(
                artist(ARTIST, "jazz"),
                singing = Singing(languages = listOf("jpn")),
            ).withAi(listed, "Model")
        assertEquals(mapOf("jazz" to false, "blues" to false, "instrumental" to true), japanese.shown())
        assertEquals(WeakEvidence.Language("jpn", "Japanese"), japanese.weakEvidence("instrumental"))
        val sung =
            genres(
                artist(ARTIST, "jazz"),
                singing = Singing(vocalists = listOf(FEATURED)),
            ).withAi(listed, "Model")
        assertEquals(WeakEvidence.Vocalist(FEATURED), sung.weakEvidence("instrumental"))
        // A featured artist alone leaves a track's Instrumental, the AI's too, as it is.
        val featured = genres(artist(ARTIST, "jazz"), artists = listOf(ARTIST, FEATURED)).withAi(listed, "Model")
        assertEquals(false, featured.shown()["instrumental"])
        // *Search with AI*'s genres, shown with no row.
        val empty = TrackGenres(singing = Singing(languages = listOf("jpn")))
        assertEquals(WeakEvidence.Language("jpn", "Japanese"), empty.aiSignOfVocals("instrumental"))
        assertNull(empty.aiSignOfVocals("blues"))
        assertNull(TrackGenres().aiSignOfVocals("instrumental"))
    }

    @Test
    fun `the recording marked instrumental leaves Instrumental as tagged, whatever else is said`() {
        val marked =
            Singing(instrumental = true, lyrics = true, languages = listOf("jpn"), vocalists = listOf("Someone"))
        val tagged = genres(everyLevel(), artists = listOf(ARTIST, FEATURED), singing = marked)
        assertEquals(genres(everyLevel(), artists = listOf(ARTIST, FEATURED)).rows, tagged.rows)
        assertFalse(tagged.shown().getValue("instrumental"))
        assertEquals(tagged, tagged.withLyricsFound())
    }

    @Test
    fun `every other genre is weighed as before, signs of vocals or not`() {
        val singing = Singing(languages = listOf("jpn"), vocalists = listOf("Someone"))
        val labels = artist(ARTIST, "folk") + album("score")
        val before = genres(labels, recording("country" to 1), artists = listOf(ARTIST, FEATURED))
        val after = genres(labels, recording("country" to 1), artists = listOf(ARTIST, FEATURED), singing = singing)
        assertEquals(before.chips(), after.chips())
        assertEquals(
            WeakEvidence.FewVotes(musicBrainz = true, artists = 1, album = true),
            after.weakEvidence("country"),
        )
    }

    private companion object {
        const val ARTIST = "Sababa 5"
        const val FEATURED = "Yurika Hanashima"
    }
}
