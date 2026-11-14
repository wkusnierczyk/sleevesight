package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.text.APP_DATE_RULE
import app.sleevesight.core.text.US_DATES
import app.sleevesight.core.text.US_DATE_RULE
import app.sleevesight.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The song's, the artist's and the album's prompt (#522): stored, so a change to it is a reviewed diff. */
class ItemPromptTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun stored(name: String): String = Fixtures.text("prompts/$name").removeSuffix("\n")

    @Test
    fun `the stored prompts are version 1's`() {
        // A change to the template changes the stored prompts and raises the version with them (the
        // synthesis cache keys insights by it): update both together. The key insight's version 9
        // (#767) left this prompt as it was: it keeps version 8's rules and style ([ItemPrompt.contract]).
        // The rule on a line found in the track's language (#902) came without a version, the lines'
        // languages being in the evidence fingerprint. Nor did the rule on dates (#957).
        assertEquals(1, ItemPrompt.VERSION)
    }

    @Test
    fun `each asks for dates in the form given, with the style (#957)`() {
        val playing = FixtureEvidence.nowPlaying(1)
        val normalized = normalizer.normalize(playing)
        for (kind in ItemPrompt.KINDS) {
            val subject = ItemPrompt.Subject(kind, "Name")
            val style = { prompt: String ->
                prompt.substringAfter("\nSTYLE\n").substringBefore(InsightPrompt.CONTRACT_HEADING)
            }
            val app =
                ItemPrompt.build(
                    subject,
                    playing,
                    normalized,
                    FixtureEvidence.of(1),
                    false,
                    roles = ArtistRoles.of(normalized),
                )
            assertTrue(APP_DATE_RULE in style(app), kind.key)
            val us =
                ItemPrompt.build(
                    subject,
                    playing,
                    normalized,
                    FixtureEvidence.of(1),
                    false,
                    US_DATES,
                    roles = ArtistRoles.of(normalized),
                )
            assertTrue(US_DATE_RULE in style(us), kind.key)
            assertFalse(APP_DATE_RULE in us, kind.key)
        }
    }

    @Test
    fun `the song's, model knowledge off`() {
        val playing = FixtureEvidence.nowPlaying(1)
        val normalized = normalizer.normalize(playing)
        val subject = ItemPrompt.Subject(CardKind.SONG, normalized.title)
        assertEquals(
            stored("item-song-1.txt"),
            ItemPrompt.build(
                subject,
                playing,
                normalized,
                FixtureEvidence.of(1),
                allowModel = false,
                roles = ArtistRoles.of(normalized),
            ),
        )
    }

    @Test
    fun `the artist's, model knowledge on`() {
        val playing = FixtureEvidence.nowPlaying(41)
        val normalized = normalizer.normalize(playing)
        val subject = ItemPrompt.Subject(CardKind.ARTIST, normalized.primary)
        assertEquals(
            stored("item-artist-41.txt"),
            ItemPrompt.build(
                subject,
                playing,
                normalized,
                FixtureEvidence.of(41),
                allowModel = true,
                roles = ArtistRoles.of(normalized),
            ),
        )
    }

    private val at = Instant.parse("2026-11-14T12:00:00Z")
    private val sonata = "https://en.wikipedia.org/wiki/Piano_Sonata_No._16_(Mozart)"
    private val k545Lines =
        listOf(
            EvidenceDraft(
                SourceId.WIKIPEDIA,
                Scope.SONG,
                "The Piano Sonata No. 16 in C major, K. 545, by Wolfgang Amadeus Mozart was described by " +
                    "Mozart in his own thematic catalogue as \"a little piano sonata for beginners\".",
                sonata,
                at,
            ),
            EvidenceDraft(
                SourceId.WIKIDATA,
                Scope.SONG,
                "Piano Sonata No. 16 was created on June 26, 1788.",
                "https://www.wikidata.org/wiki/Q991701",
                at,
            ),
            EvidenceDraft(
                SourceId.PLAYER,
                Scope.RELEASE,
                "This recording is on the release \"Mozart: Piano Concertos Nos. 20 & 23\" (2024).",
                null,
                at,
            ),
            EvidenceDraft(
                SourceId.WIKIPEDIA,
                Scope.ARTIST,
                "Wolfgang Amadeus Mozart was a prolific and influential composer of the Classical period.",
                null,
                at,
                about = "Wolfgang Amadeus Mozart",
            ),
            EvidenceDraft(
                SourceId.WIKIPEDIA,
                Scope.ARTIST,
                "Khatia Buniatishvili is a Georgian-French concert pianist.",
                null,
                at,
                about = "Khatia Buniatishvili",
            ),
        )

    // K. 545 by Buniatishvili (#1133), its roles telling Mozart apart: the sonata's lines, the release,
    // and, as when its own lines only list it, the composer's and the lead performer's.
    private fun k545(): String {
        val playing =
            NowPlaying(
                title = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
                artists = "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
                album = "Mozart: Piano Concertos Nos. 20 & 23",
                player = "com.spotify.music",
            )
        val normalized = normalizer.normalize(playing)
        return ItemPrompt.build(
            ItemPrompt.Subject(CardKind.SONG, normalized.title),
            playing,
            normalized,
            EvidenceSet.of(k545Lines, JvmHasher),
            allowModel = false,
            onlyListings = true,
            roles =
                ArtistRoles(
                    listOf("Wolfgang Amadeus Mozart"),
                    listOf("Khatia Buniatishvili", "Academy of St. Martin in the Fields"),
                    classical = true,
                ),
            parts = true,
        )
    }

    @Test
    fun `the song's in two parts, its people's lines named by their roles (#1133)`() {
        assertEquals(3, ItemPrompt.PARTS_VERSION)
        assertEquals(stored("item-song-parts.txt"), k545())
    }

    @Test
    fun `the two parts are the song's only, and ask for the rule on a gap whatever the listings say`() {
        val parts = ItemPrompt.contract(allowModel = false, parts = true)
        assertTrue(ItemPrompt.PARTS_PLAIN_TEXT_RULE in parts)
        assertFalse(InsightPrompt.PLAIN_TEXT_RULE in parts)
        assertTrue(ItemPrompt.GAP_RULE in k545())
        assertFalse(ItemPrompt.ONLY_LISTINGS_RULE in k545())
        // Asked of the artist's, the parts change nothing.
        val playing = FixtureEvidence.nowPlaying(41)
        val normalized = normalizer.normalize(playing)
        val artist = ItemPrompt.Subject(CardKind.ARTIST, normalized.primary)
        val roles = ArtistRoles.of(normalized)
        assertEquals(
            ItemPrompt.build(artist, playing, normalized, FixtureEvidence.of(41), true, roles = roles),
            ItemPrompt.build(artist, playing, normalized, FixtureEvidence.of(41), true, roles = roles, parts = true),
        )
    }

    @Test
    fun `the rule on listings stands in for the rule on a gap only when asked, and the version stays`() {
        assertEquals(1, ItemPrompt.VERSION)
        assertTrue(ItemPrompt.GAP_RULE in ItemPrompt.contract(allowModel = false))
        assertFalse(ItemPrompt.ONLY_LISTINGS_RULE in ItemPrompt.contract(allowModel = false))
        val listings = ItemPrompt.contract(allowModel = false, onlyListings = true)
        assertTrue(ItemPrompt.ONLY_LISTINGS_RULE in listings)
        assertFalse(ItemPrompt.GAP_RULE in listings)
        assertTrue("The sources give only release details for this song." in ItemPrompt.ONLY_LISTINGS_RULE)
    }
}
