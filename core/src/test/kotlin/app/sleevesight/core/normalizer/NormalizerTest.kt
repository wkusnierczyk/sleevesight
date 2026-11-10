package app.sleevesight.core.normalizer

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.fixtures.RegressionCase
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

    // #1161: a remaster, mono and stereo together, with years, are one suffix; the words in a title
    // of its own are not.
    @Test
    fun `a mono or stereo remaster suffix is stripped and noted, and the title's own words are kept`() {
        val track = normalizer.normalize("Time of the Season - Mono Remastered", "The Zombies")
        assertEquals("Time of the Season", track.title)
        assertEquals("Mono Remastered", track.dropped)
        assertEquals(listOf("drop \"Mono Remastered\""), track.notes)
        assertEquals("the zombies|time of the season", track.lookupKey)
        for (suffix in listOf(
            "Stereo Remastered",
            "Remastered Mono",
            "Remastered Stereo",
            "2019 Mono Remaster",
            "2019 Stereo Remaster",
            "Mono Remastered 2019",
            "Remastered 2009 Mono",
            "Stereo",
            "stereo",
            "MONO REMASTERED",
            "Mono  Remastered",
        )) {
            val normalized = normalizer.normalize("Song - $suffix", "A")
            assertEquals("Song", normalized.title, suffix)
            assertEquals(suffix, normalized.dropped, suffix)
        }
        // Only the last " - " part goes, as with an edit.
        assertEquals("Song - Mono Remastered", normalizer.normalize("Song - Mono Remastered - Live", "A").title)
        assertEquals("Song - Live", normalizer.normalize("Song - Live - Mono Remastered", "A").title)
        for (title in listOf(
            "Mono",
            "Stereo",
            "Stereo Love",
            "Song - Stereo Love",
            "Song - Mono Lake",
            "Song - Monophonic",
            "Song - Stereophonic",
            "Song - 2019",
            "Song - Remastered Love",
            "Song-Mono Remastered",
        )) {
            assertEquals(title, normalizer.normalize(title, "A").title, title)
        }
    }

    @Test
    fun `at most six mono and remaster words are one suffix`() {
        // The words are a bounded repeat, so matching them never recurses once per word.
        assertEquals("Song", normalizer.normalize("Song - 2019 Mono Remastered Stereo Remaster 2020", "A").title)
        val seven = "Song - 2019 Mono Remastered Stereo Remaster 2020 Mono"
        assertEquals(seven, normalizer.normalize(seven, "A").title)
        val parts = List(PARTS) { "Mono Remastered" }.joinToString(" - ")
        val kept = "Song - $parts".removeSuffix(" - Mono Remastered")
        assertEquals(kept, normalizer.normalize("Song - $parts", "A").title)
    }

    // #1060: any trailing edit, not only a radio or single one.
    @Test
    fun `any edit suffix is stripped and noted`() {
        val track = normalizer.normalize("Heaven Is a Place on Earth - Promo 7\" Edit", "Belinda Carlisle")
        assertEquals("Heaven Is a Place on Earth", track.title)
        assertEquals("Promo 7\" Edit", track.dropped)
        assertEquals(listOf("drop \"Promo 7\" Edit\""), track.notes)
        assertEquals("belinda carlisle|heaven is a place on earth", track.lookupKey)
        for (suffix in listOf("7\" Edit", "Edit", "edit", "EDIT", "12\" Promo Edit", "Single Edit")) {
            assertEquals("Song", normalizer.normalize("Song - $suffix", "A").title, suffix)
        }
    }

    @Test
    fun `an edit in the title itself is kept`() {
        for (title in listOf(
            "The Final Edit",
            "Edit Me",
            "Song-Edit",
            "Song - Credit",
            "Song - Edited",
            "Song - Edits",
            "Song - Edit Me",
            "Song - Re-Edit",
            // A dash other than " - " before the edit: no word starts or ends with one, and only
            // " - " separates the suffix, so nothing is dropped.
            "Song - Part 2 \u2013 Radio Edit",
            "Song - Live \u2014 Edit",
            "Song - Part 2 \u2212 Edit",
            "Song - Part 2 \u2010 Edit",
            "Song - Part 2- Radio Edit",
            "Song - Part 2 -Radio Edit",
        )) {
            assertEquals(title, normalizer.normalize(title, "A").title, title)
        }
        // A dash within a word is the word's.
        assertEquals("Song", normalizer.normalize("Song - Re-Recorded Edit", "A").title)
        // Only the last " - " part goes: the one before it is the title's, as with "Radio Edit".
        assertEquals("Song - 2011 Remaster", normalizer.normalize("Song - 2011 Remaster - Radio Edit", "A").title)
        assertEquals("Song - Part 2", normalizer.normalize("Song - Part 2 - Edit", "A").title)
    }

    @Test
    fun `a mix or promo suffix is kept`() {
        for (title in listOf("Song - Extended Mix", "Song - 12\" Mix", "Song - Remix", "Song - Promo")) {
            assertEquals(title, normalizer.normalize(title, "A").title, title)
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
    fun `every fixture title normalizes as stored`() {
        val expected = Json.decodeFromString<List<Expected>>(Fixtures.text("below-soundtrack/normalized.json"))
        // The stored results cover exactly these entries, in this order.
        assertEquals(
            BelowSoundtrack.tracks.map { Triple(it.n, it.title, it.artist) },
            expected.map { Triple(it.n, it.title, it.artist) },
        )
        for ((track, want) in BelowSoundtrack.tracks.zip(expected.map { it.normalized })) {
            assertMatches(want, track.title, track.artist, "track ${track.n}, ${track.title}")
        }
    }

    // ASCII-only case matching (no `ſ`, `ı` or Kelvin sign folding), JavaScript whitespace, the
    // word boundary before `version`, and the key keeping the dotless `ı` (#72, see `Normalizer`).
    @Test
    fun `edge cases normalize as stored`() {
        val expected = Json.decodeFromString<List<Expected>>(resource("/normalizer/edge-cases.normalized.json"))
        val inputs = Json.decodeFromString<List<Input>>(resource("/normalizer/edge-cases.json"))
        assertEquals(inputs.map { it.title to it.artist }, expected.map { it.title to it.artist })
        for (entry in expected) {
            assertMatches(entry.normalized, entry.title, entry.artist, "edge case ${entry.n}, ${entry.title}")
        }
        assertEquals("Song - remaſtered", normalizer.normalize("Song - remaſtered", "A").title)
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
    fun `variation selectors and tags leave the letters around them as they were`() {
        assertEquals(
            normalizer.normalize("葛飾", "A").lookupKey,
            normalizer.normalize("葛\uDB40\uDD00飾", "A").lookupKey,
        )
        val key = { title: String -> normalizer.normalize(title, "Кино").lookupKey }
        assertEquals(key("Мой"), key("Мои\uDB40\uDD00\u0306"), "the breve after the selector stays")
        assertNotEquals(key("Мои"), key("Мои\uDB40\uDD00\u0306"))
    }

    @Test
    fun `an enclosing mark is dropped`() {
        assertEquals("a|1", normalizer.normalize("1\uFE0F\u20E3", "A").lookupKey)
    }

    @Test
    fun `a title or artist of symbols only keys as itself`() {
        val key = { title: String, artist: String -> normalizer.normalize(title, artist).lookupKey }

        assertNotEquals(key("😀", "A"), key("😢", "A"))
        assertNotEquals(key("★", "A"), key("☆", "A"))
        assertEquals("a|★", key("★", "A"))
        assertEquals("!!!|song", key("Song", "!!!"))
        assertEquals("a|song", key("Song 😀", "A"), "a part that keeps something is folded as before")
        assertEquals("|song", key("Song", ""), "a blank part stays empty")
        assertEquals(key("★", "A"), key("★\uFE0F", "A"), "a variation selector is dropped here too")
        assertEquals("a|★", key("★\u20DD", "A"), "and an enclosing mark")
        assertEquals("|song", key("Song", "\uFE0F"), "a part of selectors only keys as empty")
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

    private companion object {
        // Enough " - " parts to overflow the stack if each one took a frame.
        const val PARTS = 20_000
    }
}
