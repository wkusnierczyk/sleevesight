package app.sleevesight.core.genres

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.wantsLibrary
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The check of a track's vocals (#998): which genres call for it (a weak Instrumental with a sign of
 * vocals, the artist's hint, or no Instrumental where the work credits composers alone; never with a
 * free sign settling it, nor on every track), and what each answer does to the rows and chips; and the
 * work's lyricist credit, a sign of vocals as a vocal credit is.
 */
class VocalsCheckTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val instrumental = vocabulary.labelsFor(listOf("instrumental"), level = GenreLevel.TRACK).single()

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    // *Nasnusa* (Sababa 5, Yurika Hanashima): Sababa 5's Instrumental, weak for the featured singer.
    private fun nasnusa(singing: Singing = Singing.NONE): TrackGenres =
        combineGenres(
            mapOf(LabelSource.LASTFM to artist(SABABA, "instrumental", "funk")),
            listOf(SABABA, YURIKA),
            singing,
        )

    // *Eros* by islandman (#1000): no tag of Instrumental, the artist's web line calling the act so.
    private fun eros(singing: Singing = Singing.NONE): TrackGenres =
        combineGenres(
            mapOf(LabelSource.LASTFM to artist(ISLANDMAN, "ambient", "electronic", "house")),
            listOf(ISLANDMAN),
            singing,
        ).withDescribed(describedGenres(vocabulary, listOf(EROS_LINE), listOf(ISLANDMAN)))

    // *A Bite of Sand* by Shay Hazan: Jazz and Electronic, no sign either way, or composers alone.
    private fun bite(singing: Singing = Singing.NONE): TrackGenres =
        combineGenres(mapOf(LabelSource.LASTFM to artist(HAZAN, "jazz", "electronic")), listOf(HAZAN), singing)

    private fun TrackGenres.chip(identity: String) = chips().firstOrNull { it.label.genre.identity == identity }

    @Test
    fun `a weak Instrumental with a sign of vocals, the artist's hint, and composers alone each call for the check`() {
        assertTrue(nasnusa().wantsVocalsCheck())
        assertTrue(
            combineGenres(
                mapOf(LabelSource.LASTFM to artist(SABABA, "instrumental")),
                listOf(SABABA),
                Singing(vocalists = listOf("Ana")),
            ).wantsVocalsCheck(),
        )
        assertTrue(eros().wantsVocalsCheck())
        assertTrue(bite(Singing(composersOnly = true)).wantsVocalsCheck())
    }

    @Test
    fun `no sign, a free sign settling it, or Instrumental weak for its votes alone call for none`() {
        // No sign either way: not every track is checked.
        assertFalse(bite().wantsVocalsCheck())
        // A backed Instrumental, with nothing against it.
        assertFalse(
            combineGenres(
                mapOf(LabelSource.LASTFM to artist(SABABA, "instrumental")),
                listOf(SABABA),
            ).wantsVocalsCheck(),
        )
        // Lyrics found (MusicBrainz's links, LRCLIB's), the recording marked instrumental.
        assertFalse(nasnusa(Singing(lyrics = true)).wantsVocalsCheck())
        assertFalse(nasnusa(Singing(library = LibraryLyrics.LYRICS)).wantsVocalsCheck())
        assertFalse(nasnusa(Singing(instrumental = true)).wantsVocalsCheck())
        assertFalse(nasnusa(Singing(library = LibraryLyrics.INSTRUMENTAL)).wantsVocalsCheck())
        assertFalse(eros(Singing(instrumental = true)).wantsVocalsCheck())
        assertFalse(bite(Singing(composersOnly = true, lyrics = true)).wantsVocalsCheck())
        // A track's Instrumental with one vote, weak for that alone.
        val fewVotes =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to
                        vocabulary.labelsFor(
                            listOf("instrumental"),
                            level = GenreLevel.TRACK,
                            votes =
                                mapOf(
                                    "instrumental" to 1,
                                ),
                        ),
                ),
                listOf(HAZAN),
            )
        assertIs<WeakEvidence.FewVotes>(fewVotes.weakEvidence(TrackGenres.INSTRUMENTAL))
        assertFalse(fewVotes.wantsVocalsCheck())
        // Once applied, never again.
        assertFalse(nasnusa().withVocals(VocalsAnswer.UNKNOWN, MODEL).wantsVocalsCheck())
    }

    @Test
    fun `instrumental lifts the weak mark, of a sign of vocals and of the artist's hint`() {
        val lifted = nasnusa().withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
        assertEquals(false, lifted.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertNull(lifted.weakEvidence(TrackGenres.INSTRUMENTAL))
        assertEquals(nasnusa().rows, lifted.rows)
        val hint = eros().withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
        assertEquals(false, hint.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertTrue(hint.chip(TrackGenres.INSTRUMENTAL)?.row?.described == true)
    }

    @Test
    fun `instrumental where no row has it adds it on the track's row, marked AI and credited to the model`() {
        val added = bite(Singing(composersOnly = true)).withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
        val track = added.rows.first()
        assertEquals(GenreLevel.TRACK, track.level)
        val label = track.labels.single()
        assertEquals(TrackGenres.INSTRUMENTAL, label.genre.identity)
        assertTrue(label.aiAdded)
        assertEquals(MODEL, label.aiModel)
        val chip = checkNotNull(added.chip(TrackGenres.INSTRUMENTAL))
        assertFalse(chip.weak)
        // After the sources' chips, as the AI's genres.
        assertEquals(listOf("electronic", "jazz", "instrumental"), added.chips().map { it.label.genre.identity })
    }

    @Test
    fun `vocals drop Instrumental from every row, unless a tap asked it on this play`() {
        val dropped = nasnusa().withVocals(VocalsAnswer.VOCALS, MODEL)
        assertNull(dropped.chip(TrackGenres.INSTRUMENTAL))
        assertTrue(dropped.all.none { it.genre.identity == TrackGenres.INSTRUMENTAL })
        assertEquals(listOf("funk"), dropped.all.map { it.genre.identity })
        assertNull(eros().withVocals(VocalsAnswer.VOCALS, MODEL).chip(TrackGenres.INSTRUMENTAL))
        // Asked by a tap: the chip stays weak, with its sign, until the genres are built again.
        val tapped = nasnusa().withVocals(VocalsAnswer.VOCALS, MODEL, drops = false)
        assertEquals(true, tapped.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertIs<WeakEvidence.Featured>(tapped.weakEvidence(TrackGenres.INSTRUMENTAL))
    }

    @Test
    fun `free lyrics win over an instrumental answer kept from before, from MusicBrainz or LRCLIB`() {
        for (sung in listOf(
            Singing(composersOnly = true, lyrics = true),
            Singing(composersOnly = true, library = LibraryLyrics.LYRICS),
        )) {
            val checked = bite(sung).withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
            assertTrue(checked.everyLabel.none { it.genre.identity == TrackGenres.INSTRUMENTAL })
            assertNull(checked.singing.checked)
        }
        // A tagged Instrumental, lyrics found: it stays dropped.
        val tagged =
            nasnusa(
                Singing(library = LibraryLyrics.LYRICS),
            ).withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
        assertNull(tagged.chip(TrackGenres.INSTRUMENTAL))
    }

    @Test
    fun `instrumental confirmed lifts a one-vote track tag's weak mark too`() {
        val oneVote =
            combineGenres(
                mapOf(
                    LabelSource.MUSICBRAINZ to
                        vocabulary.labelsFor(
                            listOf("instrumental"),
                            level = GenreLevel.TRACK,
                            votes =
                                mapOf(
                                    "instrumental" to 1,
                                ),
                        ),
                ),
                listOf(HAZAN),
                Singing(languages = listOf("eng")),
            )
        assertEquals(true, oneVote.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertTrue(oneVote.wantsVocalsCheck())
        val confirmed = oneVote.withVocals(VocalsAnswer.INSTRUMENTAL, MODEL, instrumental)
        assertEquals(false, confirmed.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertNull(confirmed.weakEvidence(TrackGenres.INSTRUMENTAL))
        // Unknown leaves it weak, for its votes as for its sign.
        assertEquals(true, oneVote.withVocals(VocalsAnswer.UNKNOWN, MODEL).chip(TrackGenres.INSTRUMENTAL)?.weak)
    }

    @Test
    fun `composers alone wait for LRCLIB, whose lyrics or mark settle it, and whose no match leaves the check`() {
        val hinted = bite(Singing(composersOnly = true))
        assertTrue(hinted.wantsLibrary())
        val sung = hinted.withLibrary(LibraryLyrics.LYRICS)
        assertFalse(sung.wantsVocalsCheck())
        assertFalse(sung.wantsLibrary())
        val marked = hinted.withLibrary(LibraryLyrics.INSTRUMENTAL)
        assertFalse(marked.wantsVocalsCheck())
        // LRCLIB gives no genre: its mark settles the check and adds none.
        assertTrue(marked.everyLabel.none { it.genre.identity == TrackGenres.INSTRUMENTAL })
        val none = hinted.withLibrary(LibraryLyrics.NONE)
        assertFalse(none.wantsLibrary())
        assertTrue(none.wantsVocalsCheck())
    }

    @Test
    fun `unknown keeps the weak mark and its line`() {
        val unknown = nasnusa().withVocals(VocalsAnswer.UNKNOWN, MODEL)
        assertEquals(true, unknown.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertEquals(WeakEvidence.Featured(YURIKA), unknown.weakEvidence(TrackGenres.INSTRUMENTAL))
        assertTrue(
            bite(Singing(composersOnly = true)).withVocals(VocalsAnswer.UNKNOWN, MODEL, instrumental).all.none {
                it.genre.identity == TrackGenres.INSTRUMENTAL
            },
        )
    }

    @Test
    fun `a lyricist credited on the work leaves Instrumental weak, named after a vocalist`() {
        val credited = nasnusa(Singing(lyricists = listOf("Yurika Hanashima")))
        // A weak sign, as a vocal credit: the check is still wanted, with no other sign too.
        assertTrue(credited.wantsVocalsCheck())
        val alone =
            combineGenres(
                mapOf(LabelSource.LASTFM to artist(SABABA, "instrumental")),
                listOf(SABABA),
                Singing(lyricists = listOf("Ben")),
            )
        assertIs<WeakEvidence.Lyricist>(alone.weakEvidence(TrackGenres.INSTRUMENTAL))
        assertTrue(alone.wantsVocalsCheck())
        assertEquals(true, credited.chip(TrackGenres.INSTRUMENTAL)?.weak)
        assertEquals(WeakEvidence.Lyricist("Yurika Hanashima"), credited.weakEvidence(TrackGenres.INSTRUMENTAL))
        val both = nasnusa(Singing(vocalists = listOf("Ana"), lyricists = listOf("Ben")))
        assertEquals(WeakEvidence.Vocalist("Ana"), both.weakEvidence(TrackGenres.INSTRUMENTAL))
        // The recording marked instrumental stands, whatever the credits say.
        assertNull(
            nasnusa(Singing(instrumental = true, lyricists = listOf("Ben"))).weakEvidence(TrackGenres.INSTRUMENTAL),
        )
    }

    private companion object {
        const val SABABA = "Sababa 5"
        const val YURIKA = "Yurika Hanashima"
        const val ISLANDMAN = "islandman"
        const val HAZAN = "Shay Hazan"
        const val MODEL = "Claude Haiku 5.5"

        val EROS_LINE =
            EvidenceLine(
                "S8",
                SourceId.WEB,
                Scope.ARTIST,
                "Islandman is described as an instrumental electro-acoustic, neo-psychedelic band from Turkey " +
                    "that reimagines the deep-rooted sounds of Anatolia.",
                "https://example.org/S8",
                Instant.parse("2026-11-06T00:00:00Z"),
            )
    }
}
