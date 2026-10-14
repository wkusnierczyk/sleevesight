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
import kotlin.test.assertNotEquals
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

    // Deviations from the simulator, by design (see `Normalizer`).

    @Test
    fun `an empty artist string gives an empty primary artist`() {
        val track = normalizer.normalize("Song", "")
        assertEquals(emptyList(), track.artists)
        assertEquals("", track.primary)
        assertEquals("|song", track.lookupKey)
    }

    @Test
    fun `with an empty artist string, a featured artist becomes the primary`() {
        val track = normalizer.normalize("Song (feat. B)", "")
        assertEquals(listOf("B"), track.artists)
        assertEquals("B", track.primary)
        assertEquals("b|song", track.lookupKey)
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
        // The oracle was run over exactly these entries, in this order.
        assertEquals(
            BelowSoundtrack.tracks.map { Triple(it.n, it.title, it.artist) },
            expected.map { Triple(it.n, it.title, it.artist) },
        )
        for ((track, want) in BelowSoundtrack.tracks.zip(expected.map { it.normalized })) {
            assertMatches(want, track.title, track.artist, "track ${track.n}, ${track.title}")
        }
    }

    // ASCII-only case matching (no `ſ`, `ı` or Kelvin sign folding), JavaScript whitespace, the
    // word boundary before `version`: the simulator's output from `normalized.js`.
    @Test
    fun `edge cases normalize as the simulator does`() {
        val expected = Json.decodeFromString<List<Expected>>(resource("/normalizer/edge-cases.normalized.json"))
        val inputs = Json.decodeFromString<List<Input>>(resource("/normalizer/edge-cases.json"))
        assertEquals(inputs.map { it.title to it.artist }, expected.map { it.title to it.artist })
        // The one documented deviation (#72, see `Normalizer`): the key keeps the dotless `ı`, which
        // the simulator dropped.
        val keyDeviations = mapOf(1 to "a|song lıve")
        for (entry in expected) {
            val want = keyDeviations[entry.n]?.let { entry.normalized.copy(key = it) } ?: entry.normalized
            assertMatches(want, entry.title, entry.artist, "edge case ${entry.n}, ${entry.title}")
        }
        assertEquals("Song - ſingle edit", normalizer.normalize("Song - ſingle edit", "A").title)
    }

    // #72: the key keeps letters and digits in any script, and `+`.

    @Test
    fun `non-Latin titles and artists give distinct keys`() {
        val tracks =
            listOf(
                "ファースト・ラヴ" to "宇多田ヒカル",
                "戦場のメリークリスマス" to "坂本龍一",
                "Группа крови" to "Кино",
                "Σ'αγαπώ" to "Νίκος",
                "First Love" to "宇多田ヒカル",
                "First Love" to "坂本龍一",
                "C++" to "AC/DC",
                "C" to "AC DC",
                "ガラス" to "A",
                "カラス" to "A",
            )
        val keys = tracks.map { (title, artist) -> normalizer.normalize(title, artist).lookupKey }

        assertEquals(keys.size, keys.toSet().size, keys.joinToString())
        assertEquals("宇多田ヒカル|ファースト ラヴ", keys[0])
        assertEquals("кино|группа крови", keys[2])
        assertEquals("νικος|σ αγαπω", keys[3])
        assertEquals("ac dc|c++", keys[6])
        assertEquals("ac dc|c", keys[7])
    }

    @Test
    fun `a mark that tells letters apart outside Latin and Greek is kept`() {
        val key = { title: String -> normalizer.normalize(title, "Кино").lookupKey }

        assertEquals("кино|мои\u0306", key("Мой"))
        assertEquals("кино|мои", key("Мои"))
        assertEquals(key("Мой"), key("Мои\u0306"), "precomposed and decomposed й agree")
        assertNotEquals(key("ў"), key("у"))
        assertNotEquals(key("ї"), key("і"))
        // Latin and Greek accents still fold.
        assertEquals("beyonce|deja vu", normalizer.normalize("Déjà Vu", "Beyoncé").lookupKey)
        assertEquals("beyonce|deja vu", normalizer.normalize("De\u0301ja\u0300 Vu", "Beyonce\u0301").lookupKey)
        assertEquals("νικος|σ αγαπω", normalizer.normalize("Σ'αγαπώ", "Νίκος").lookupKey)
    }

    @Test
    fun `symbols and emoji are dropped in either plane`() {
        for (title in listOf("Song ★", "Song 😀", "Song ❤️", "Song 👍🏽", "Song 𝄞")) {
            assertEquals("a|song", normalizer.normalize(title, "A").lookupKey, title)
        }
    }

    @Test
    fun `a compatibility form keys as its plain letter`() {
        assertEquals("a|song", normalizer.normalize("Ｓｏｎｇ", "Ａ").lookupKey)
    }

    @Test
    fun `supplementary characters are kept whole`() {
        assertEquals("a|\uD840\uDC0B", normalizer.normalize("\uD840\uDC0B", "A").lookupKey)
    }

    private fun assertMatches(
        want: ExpectedTrack,
        title: String,
        artist: String,
        message: String,
    ) {
        val actual = normalizer.normalize(title, artist)
        assertEquals(want.title, actual.title, message)
        assertEquals(want.artists, actual.artists, message)
        assertEquals(want.primary, actual.primary, message)
        assertEquals(want.from, actual.from, message)
        assertEquals(want.key, actual.lookupKey, message)
        assertEquals(want.notes, actual.notes, message)
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "no $path" }.use { it.readBytes().decodeToString() }

    @Serializable
    private data class Input(
        val title: String,
        val artist: String,
    )

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
