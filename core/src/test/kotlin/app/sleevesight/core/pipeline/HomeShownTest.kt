package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.MusicBrainzEvidence
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Whether a track's evidence holds anything beyond what Home shows of it (#1142). */
class HomeShownTest {
    private val playing =
        NowPlaying(
            title = "The Campaign for Real Rock",
            artists = "Edwyn Collins",
            album = "Gorgeous George",
            player = "p",
            duration = 200.seconds,
        )

    private fun nothingBeyond(
        lines: List<EvidenceDraft>,
        nowPlaying: NowPlaying = playing,
    ): Boolean =
        HomeShown.nothingBeyond(
            EvidenceSet.of(playerLines(nowPlaying, AT) + lines, JvmHasher),
            nowPlaying,
            Normalizer(JvmAccentFolder).normalize(nowPlaying),
        )

    // MusicBrainz's lines for the track, as its templates write them.
    private fun musicBrainz(
        credits: List<Credit> = emptyList(),
        release: FirstRelease? = FirstRelease("1994-07-01", "Gorgeous George", "rg", "r", "Album"),
        cover: Boolean = false,
    ): List<EvidenceDraft> =
        MusicBrainzEvidence.lines(
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = "rec",
                title = "The Campaign for Real Rock",
                artistCredit = "Edwyn Collins",
                firstRelease = release,
                work = ResolvedWork("work", if (cover) "Campaign" else "The Campaign for Real Rock"),
                cover = cover,
                credits = credits,
            ),
            AT,
        )

    private fun credit(
        role: CreditRole,
        name: String,
    ) = Credit(role, name, "mb-$name")

    private fun draft(
        source: SourceId,
        scope: Scope,
        text: String,
        aggregate: Boolean = false,
    ) = EvidenceDraft(source, scope, text, null, AT, aggregate = aggregate)

    @Test
    fun `the release, its date and a credit naming the artist shown add nothing beyond Home`() {
        // The issue's track: its card was the header in prose.
        val lines =
            musicBrainz(listOf(credit(CreditRole.WRITER, EDWYN), credit(CreditRole.PERFORMER, EDWYN)))
        assertEquals(
            listOf(
                "“The Campaign for Real Rock” by Edwyn Collins was first released on 1 July 1994, on the album " +
                    "“Gorgeous George”.",
                "“The Campaign for Real Rock” was written by Edwyn Collins.",
                "Edwyn Collins performs on this recording of “The Campaign for Real Rock”.",
            ),
            lines.map { it.text },
        )
        assertTrue(nothingBeyond(lines))
        // The player's release line alone, and with Wikidata's date and Last.fm's counts and tags.
        assertTrue(nothingBeyond(emptyList()))
        val more =
            listOf(
                draft(SourceId.WIKIDATA, Scope.SONG, "“The Campaign for Real Rock” was first published in 1994."),
                draft(SourceId.LASTFM, Scope.ARTIST, "Last.fm counts 100 listeners for $EDWYN.", aggregate = true),
                draft(SourceId.LASTFM, Scope.ARTIST, "Last.fm listeners tag $EDWYN as: indie.", aggregate = true),
                draft(
                    SourceId.MUSICBRAINZ,
                    Scope.RELEASE,
                    "“Gorgeous George” is an album by Edwyn Collins, first released in July 1994.",
                ),
            )
        assertTrue(nothingBeyond(lines + more))
    }

    @Test
    fun `a producer, a session player, another writer, an earlier release or a source's text add something`() {
        val shown = musicBrainz(listOf(credit(CreditRole.WRITER, "Edwyn Collins")))
        val beyond =
            mapOf(
                "a producer" to musicBrainz(listOf(credit(CreditRole.PRODUCER, "Paul Kimble"))),
                "a session player" to musicBrainz(listOf(credit(CreditRole.PERFORMER, "Clem Burke"))),
                "a writer besides the artist" to
                    musicBrainz(listOf(credit(CreditRole.WRITER, EDWYN), credit(CreditRole.WRITER, "Kimble"))),
                "a composer other than the artist" to musicBrainz(listOf(credit(CreditRole.COMPOSER, "Ann Other"))),
                "an earlier release" to musicBrainz(release = SINGLE),
                "another album" to musicBrainz(release = FirstRelease("1993-01-01", "Demos", "rg", "r", "Album")),
                "a cover" to musicBrainz(cover = true),
                "a Wikipedia sentence" to listOf(draft(SourceId.WIKIPEDIA, Scope.SONG, "It was recorded in London.")),
                "a web page" to listOf(draft(SourceId.WEB, Scope.WEB, "A review of the song.")),
                "an artist's biography" to
                    listOf(draft(SourceId.LASTFM, Scope.ARTIST, "The Last.fm biography of Edwyn Collins says: \"…\"")),
                "a genre" to
                    listOf(draft(SourceId.WIKIDATA, Scope.SONG, "The genre of “The Campaign for Real Rock” is rock.")),
                "a band's formation" to
                    listOf(draft(SourceId.WIKIDATA, Scope.ARTIST, "“Orange Juice” was formed in 1979.")),
            )
        assertTrue(nothingBeyond(shown))
        for ((what, lines) in beyond) assertFalse(nothingBeyond(shown + lines), what)
    }

    @Test
    fun `the release is the player's album, a single or an EP only when the player names it so`() {
        val single = musicBrainz(release = SINGLE)
        assertFalse(nothingBeyond(single), "a single before the album is an earlier release")
        assertTrue(nothingBeyond(single, playing.copy(album = "The Campaign for Real Rock - Single")))
        // An edition of the album is the album; no album reported, nothing is shown to restate.
        assertTrue(nothingBeyond(musicBrainz(), playing.copy(album = "Gorgeous George (Remastered 2011)")))
        assertFalse(nothingBeyond(musicBrainz(), playing.copy(album = null)))
    }

    @Test
    fun `a credit's names are only the artists shown, whole names, any of them`() {
        val artists = listOf("edwyn collins", "roddy frame")
        assertTrue(HomeShown.namesOnly("Edwyn Collins", artists))
        assertTrue(HomeShown.namesOnly("Edwyn Collins and Roddy Frame", artists))
        assertTrue(HomeShown.namesOnly("Roddy Frame, Edwyn Collins and Roddy Frame", artists))
        assertFalse(HomeShown.namesOnly("Edwyn Collins Jr.", artists))
        assertFalse(HomeShown.namesOnly("Edwyn Collins, Paul Kimble and Roddy Frame", artists))
        assertFalse(HomeShown.namesOnly("Edwyn", artists))
    }

    @Test
    fun `the credit and release readers read MusicBrainz's own templates`() {
        val lines =
            musicBrainz(
                listOf(
                    credit(CreditRole.COMPOSER, "A"),
                    credit(CreditRole.LYRICIST, "B"),
                    credit(CreditRole.PRODUCER, "C"),
                    Credit(CreditRole.PERFORMER, "D", "d", listOf("guitar", "vocals")),
                ),
            ).map { it.text }
        assertEquals(
            listOf(null, "A", "B", "C", "D"),
            lines.map(MusicBrainzEvidence::creditedNames),
        )
        assertEquals("album" to "Gorgeous George", MusicBrainzEvidence.firstRelease(lines.first()))
        assertNull(MusicBrainzEvidence.firstRelease(lines[1]))
    }

    private companion object {
        val AT: Instant = Instant.fromEpochSeconds(1_700_000_000)
        const val EDWYN = "Edwyn Collins"
        val SINGLE = FirstRelease("1994-05-01", "The Campaign for Real Rock", "rg", "r", "Single")
    }
}
