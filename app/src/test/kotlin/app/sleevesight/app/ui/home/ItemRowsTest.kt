package app.sleevesight.app.ui.home

import app.sleevesight.app.R
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.WorkLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The names the rows under *Key insights* go by (#1160): the song's title as under the cover, with
 * the version the normalizer dropped kept for its panel; a featured artist, a source work and a
 * variant left out as under the cover, with no note; the player's whole title, with no version, when
 * normalizing leaves no title.
 */
class ItemRowsTest {
    private fun song(title: String): ItemRow {
        val header = sundown.copy(title = title)
        return itemRows(header, normalizer.normalize(header)).single { it.item == InsightItem.SONG }
    }

    @Test
    fun aDroppedVersionIsLeftOutOfTheNameAndKept() {
        val row = song("Material Girl - 2024 Remaster")
        assertEquals("Material Girl", row.name)
        assertEquals("2024 Remaster", row.version)
    }

    @Test
    fun aVariantIsLeftOutAsUnderTheCoverWithNoVersion() {
        val row = song("Sundown (Acoustic)")
        assertEquals("Sundown", row.name)
        assertNull(row.version)
    }

    @Test
    fun aFeaturedArtistAndASourceWorkAreLeftOutAsUnderTheCover() {
        assertEquals("Sundown", song("Sundown (feat. Anne Murray)").name)
        assertEquals("Sundown", song("Sundown (From \"A Film\")").name)
        assertNull(song("Sundown (feat. Anne Murray)").version)
    }

    @Test
    fun aTitleNormalizingLeavesEmptyKeepsThePlayersWholeTitleAndNoVersion() {
        val title = "(feat. Anne Murray) - Live"
        // What the fallback is for: nothing left, a version dropped.
        val normalized = normalizer.normalize(sundown.copy(title = title))
        assertEquals("", normalized.title)
        assertEquals("Live", normalized.dropped)
        val row = song(title)
        assertEquals(title, row.name)
        assertNull(row.version)
    }

    @Test
    fun aPlainTitleIsTheNameWithNoVersion() {
        val row = song("Sundown")
        assertEquals("Sundown", row.name)
        assertNull(row.version)
    }

    @Test
    fun theComposerRowIsCalledAsItsAuthorsAreAndNamesThemAll() {
        // #1199: Composer, Author or Authors, its name and its button's action.
        val header = sundown.copy(title = "Nessun Dorma", artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta")
        val normalized = normalizer.normalize(header)
        val roles = ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta"))

        fun composer(lyrics: WorkLyrics?) =
            itemRows(header, normalized, ArtistSplit.of(normalized, roles, lyrics)).single {
                it.item ==
                    InsightItem.COMPOSER
            }
        val one = composer(null)
        assertEquals("Giacomo Puccini", one.name)
        assertEquals(R.string.home_item_composer to R.string.home_item_show_composer, one.kind to one.action)
        val author = composer(WorkLyrics(lyricists = listOf("Giacomo Puccini")))
        assertEquals("Giacomo Puccini", author.name)
        assertEquals(R.string.home_item_author to R.string.home_item_show_author, author.kind to author.action)
        val authors = composer(WorkLyrics(lyricists = listOf("Giuseppe Adami", "Renato Simoni")))
        assertEquals("Giacomo Puccini, Giuseppe Adami, Renato Simoni", authors.name)
        assertEquals(R.string.home_item_authors to R.string.home_item_show_authors, authors.kind to authors.action)
        // An aria's librettists (#1274) are authors as its lyricists are.
        val libretto = composer(WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni")))
        assertEquals("Giacomo Puccini, Giuseppe Adami, Renato Simoni", libretto.name)
        assertEquals(R.string.home_item_authors, libretto.kind)
        val rows = itemRows(header, normalized, ArtistSplit.of(normalized, roles, null))
        assertEquals(
            listOf(InsightItem.SONG, InsightItem.COMPOSER, InsightItem.ARTIST, InsightItem.ALBUM),
            rows.map { it.item },
        )
    }

    @Test
    fun theArtistRowIsPerformerSingularWhenTheComposerIsToldApart() {
        // #1276: Performer, by the lead performer, with other performers known or not.
        val header = sundown.copy(title = "Nessun Dorma", artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta")
        val normalized = normalizer.normalize(header)

        fun artist(performers: List<String>) =
            itemRows(
                header,
                normalized,
                ArtistSplit.of(normalized, ArtistRoles(listOf("Giacomo Puccini"), performers), null),
            ).single { it.item == InsightItem.ARTIST }
        for (performers in listOf(listOf("Luciano Pavarotti", "Zubin Mehta"), listOf("Luciano Pavarotti"))) {
            val row = artist(performers)
            assertEquals("Luciano Pavarotti", row.name)
            assertEquals(R.string.home_item_performer to R.string.home_item_show_performer, row.kind to row.action)
        }
    }

    @Test
    fun theSongRowIsMusicOnAClassicalTrackAndSongOtherwise() {
        // #1331: by the test that tells Composer from Author, whatever the Composer row is called.
        val header = sundown.copy(title = "Nessun Dorma", artists = "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta")
        val normalized = normalizer.normalize(header)
        val roles = ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta"))
        val music = R.string.home_item_song_classical to R.string.home_item_show_song_classical
        val plain = R.string.home_item_song to R.string.home_item_show_song

        fun kindOf(
            roles: ArtistRoles,
            lyrics: WorkLyrics?,
            playing: NowPlaying = header,
        ): Pair<Int, Int> {
            val read = normalizer.normalize(playing)
            val rows = itemRows(playing, read, ArtistSplit.of(read, roles, lyrics))
            val row = rows.single { it.item == InsightItem.SONG }
            return row.kind to row.action
        }
        val libretto = WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni"))
        // A classical work: Composer, or Authors with its librettists (an opera's aria).
        assertEquals(music, kindOf(roles.copy(classical = true), null))
        assertEquals(music, kindOf(roles.copy(classical = true), libretto))
        // Told by the title alone, leading with its composer (K. 545).
        val k545 =
            sundown.copy(
                title = "Mozart: Piano Sonata No. 16 in C Major, K. 545: II. Andante",
                artists = "Wolfgang Amadeus Mozart, Khatia Buniatishvili",
            )
        val mozart = ArtistRoles(listOf("Wolfgang Amadeus Mozart"), listOf("Khatia Buniatishvili"))
        assertEquals(music, kindOf(mozart, null, k545))
        // No classical work: an Author row (a cover of a songwriter's own song), Authors, or Composer.
        assertEquals(plain, kindOf(roles, WorkLyrics(lyricists = listOf("Giacomo Puccini"))))
        assertEquals(plain, kindOf(roles, libretto))
        assertEquals(plain, kindOf(roles, null))
        // No split: a pop track.
        val pop = itemRows(header, normalized).single { it.item == InsightItem.SONG }
        assertEquals(plain, pop.kind to pop.action)
    }

    @Test
    fun theArtistRowIsArtistWhenNothingTellsTheComposerApart() {
        // #1276: no split, one Artist row as before, whatever the track credits.
        val header = sundown.copy(title = "Somebody That I Used To Know", artists = "Gotye, Kimbra")
        val row = itemRows(header, normalizer.normalize(header)).single { it.item == InsightItem.ARTIST }
        assertEquals("Gotye", row.name)
        assertEquals(R.string.home_item_artist to R.string.home_item_show_artist, row.kind to row.action)
    }
}
