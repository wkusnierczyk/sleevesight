package io.github.wkusnierczyk.sleevesight.core.normalizer

import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.Fixtures
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NormalizerTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    // The design's table (`design/design.md`, *Normalizer*), one test per row.

    @Test
    fun `a version suffix is stripped and noted`() {
        val track = normalizer.normalize("Fisherman's Blues - 2006 Remaster", "The Waterboys")
        assertEquals("Fisherman's Blues", track.title)
        assertEquals(listOf("drop \"2006 Remaster\""), track.notes)
        for (suffix in listOf("demo", "Radio Edit", "Old Soul Version", "remastered 2011", "Live", "Mono")) {
            assertEquals("Song", normalizer.normalize("Song - $suffix", "A").title, suffix)
        }
    }

    @Test
    fun `a featured artist in the title moves to the artists`() {
        val track = normalizer.normalize("Baby, Please Don't Go (feat. Van Morrison)", "Them")
        assertEquals("Baby, Please Don't Go", track.title)
        assertEquals(listOf("Them", "Van Morrison"), track.artists)
        assertEquals("Them", track.primary)
        assertEquals(listOf("feat. → artists"), track.notes)
    }

    @Test
    fun `a featured artist already credited is not added twice`() {
        val track = normalizer.normalize("Baby, Please Don't Go (ft. van morrison)", "Them, Van Morrison")
        assertEquals(listOf("Them", "Van Morrison"), track.artists)
    }

    @Test
    fun `a source work is stripped and kept`() {
        val parenthesized = normalizer.normalize("The Departure (From The Leftovers)", "I Aimed My Pebble")
        assertEquals("The Departure", parenthesized.title)
        assertEquals("The Leftovers", parenthesized.from)
        val suffixed = normalizer.normalize("Theme - From \"Coronation Street\"", "London Music Works")
        assertEquals("Theme", suffixed.title)
        assertEquals("Coronation Street", suffixed.from)
        assertEquals(listOf("from: Coronation Street"), suffixed.notes)
    }

    @Test
    fun `a variant is stripped and kept`() {
        val track = normalizer.normalize("And Dream Of Sheep (felt piano)", "Joshua Greenwood")
        assertEquals("And Dream Of Sheep", track.title)
        assertEquals("felt piano", track.variant)
        assertEquals(listOf("variant: felt piano"), track.notes)
        assertEquals("acoustic", normalizer.normalize("Song (acoustic)", "A").variant)
        assertNull(normalizer.normalize("Song", "A").variant)
    }

    @Test
    fun `the artist field splits into credited artists`() {
        val track = normalizer.normalize("Up Down", "Peleboy, Tony K")
        assertEquals(listOf("Peleboy", "Tony K"), track.artists)
        assertEquals("Peleboy", track.primary)
        assertEquals("peleboy|up down", track.lookupKey)
    }

    @Test
    fun `the lookup key is lowercased and accent-folded`() {
        assertEquals(
            "kristof hajos|here comes the rain again",
            normalizer.normalize("Here Comes The Rain Again", "Kristóf Hajós").lookupKey,
        )
        assertEquals(
            "ispini na heireann|bash bash bash",
            normalizer.normalize("Bash Bash Bash", "Ispíní na hÉireann").lookupKey,
        )
    }

    // Where the port spells out the simulator's JavaScript regex classes.

    @Test
    fun `version needs a word boundary before it, as in the simulator`() {
        assertEquals("Song", normalizer.normalize("Song - version", "A").title)
        assertEquals("Song", normalizer.normalize("Song - Rock'n'Roll Version", "A").title)
        assertEquals("Song - Conversion", normalizer.normalize("Song - Conversion", "A").title)
    }

    @Test
    fun `JavaScript whitespace is trimmed and matched`() {
        assertEquals("Song", normalizer.normalize(" Song　", "A").title)
        assertEquals("Song", normalizer.normalize("Song - demo", "A").title)
        assertEquals(listOf("A", "B"), normalizer.normalize("Song", "A , B").artists)
    }

    @Test
    fun `a NowPlaying normalizes by its title and artist string`() {
        val nowPlaying =
            NowPlaying(title = "Lovely - Radio Edit", artists = "Alexander S.", player = "com.spotify.music")
        assertEquals(normalizer.normalize("Lovely - Radio Edit", "Alexander S."), normalizer.normalize(nowPlaying))
    }

    @Test
    fun `duplicates in the playlist share a lookup key`() {
        // 8 and 22, 4 and 24.
        val keys = RegressionCase.DUPLICATES.tracks.map { key(it) }
        assertEquals(keys[0], keys[1])
        assertEquals(keys[2], keys[3])
    }

    @Test
    fun `every fixture title normalizes as the simulator does`() {
        val expected = Json.decodeFromString<List<Expected>>(Fixtures.text("below-soundtrack/normalized.json"))
        assertEquals(BelowSoundtrack.tracks.size, expected.size)
        for (entry in expected) {
            val actual = normalizer.normalize(entry.title, entry.artist)
            val want = entry.normalized
            val message = "track ${entry.n}, ${entry.title}"
            assertEquals(want.title, actual.title, message)
            assertEquals(want.artists, actual.artists, message)
            assertEquals(want.primary, actual.primary, message)
            assertEquals(want.from, actual.from, message)
            assertEquals(want.key, actual.lookupKey, message)
            assertEquals(want.notes, actual.notes, message)
        }
    }

    private fun key(n: Int): String =
        BelowSoundtrack.track(n).let { normalizer.normalize(it.title, it.artist).lookupKey }

    @Serializable
    private data class Expected(
        val n: Int,
        val title: String,
        val artist: String,
        val normalized: ExpectedTrack,
    )

    @Serializable
    private data class ExpectedTrack(
        val title: String,
        val artists: List<String>,
        val primary: String,
        val from: String?,
        val key: String,
        val notes: List<String>,
    )
}
