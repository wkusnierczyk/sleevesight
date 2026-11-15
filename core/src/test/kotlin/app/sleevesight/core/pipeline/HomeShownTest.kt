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
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedWork
import app.sleevesight.core.sources.WorkLyrics
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
        authors: List<String> = emptyList(),
    ): Boolean =
        HomeShown.nothingBeyond(
            EvidenceSet.of(playerLines(nowPlaying, AT) + lines, JvmHasher),
            nowPlaying,
            Normalizer(JvmAccentFolder).normalize(nowPlaying),
            authors,
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
    fun `a performer's instruments or role add something beyond the name Home shows`() {
        val playing = Credit(CreditRole.PERFORMER, EDWYN, "mb-edwyn", listOf("guitar", "vocals"))
        val lines = musicBrainz(listOf(playing))
        assertEquals(
            "Edwyn Collins performs on this recording of “The Campaign for Real Rock” (guitar, vocals).",
            lines.last().text,
        )
        assertFalse(nothingBeyond(lines))
        assertTrue(nothingBeyond(musicBrainz(listOf(credit(CreditRole.PERFORMER, EDWYN)))))
    }

    @Test
    fun `an aria's librettists the Composer row names are shown, though the player does not name them`() {
        val aria =
            NowPlaying(
                title = "Turandot: Nessun dorma",
                artists = "Giacomo Puccini, Luciano Pavarotti",
                album = "Pavarotti's Greatest Hits",
                player = "p",
                duration = 200.seconds,
            )
        val normalized = Normalizer(JvmAccentFolder).normalize(aria)
        val roles = ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti"), classical = true)
        val libretto = WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni"))
        val split = ArtistSplit.of(normalized, roles, libretto)
        val authors = checkNotNull(split).authors
        assertEquals(listOf("Giacomo Puccini", "Giuseppe Adami", "Renato Simoni"), authors)
        val lines =
            listOf(
                draft(
                    SourceId.MUSICBRAINZ,
                    Scope.SONG,
                    "The libretto of this aria was written by Giuseppe Adami and Renato Simoni.",
                ),
                draft(SourceId.MUSICBRAINZ, Scope.SONG, "The music of “Nessun dorma” was composed by Giacomo Puccini."),
            )
        assertTrue(nothingBeyond(lines, aria, authors))
        assertFalse(nothingBeyond(lines, aria), "with no Composer row, the librettists are not shown")
    }

    @Test
    fun `Wikidata's date is shown only when Dates shows it`() {
        // *Dates* shows MusicBrainz's first release, on 1 July 1994.
        val release = musicBrainz()
        for (date in listOf("on 1 July 1994", "in 1994")) {
            val line = draft(SourceId.WIKIDATA, Scope.SONG, "“The Campaign for Real Rock” was first published $date.")
            assertTrue(nothingBeyond(release + line), date)
        }
        for (date in listOf("in 1960", "on 2 July 1994", "in July 1994")) {
            val line = draft(SourceId.WIKIDATA, Scope.SONG, "“The Campaign for Real Rock” was created $date.")
            assertFalse(nothingBeyond(release + line), date)
        }
        // With no date shown, none is.
        val alone = draft(SourceId.WIKIDATA, Scope.SONG, "“The Campaign for Real Rock” was first published in 1994.")
        assertFalse(nothingBeyond(listOf(alone)))
    }

    @Test
    fun `MusicBrainz's line on the album is read whatever its type, the secondary types first`() {
        for (type in listOf("an album", "a live album", "a compilation album", "a mixtape/street album", "an EP")) {
            val text = "“Gorgeous George” is $type by $EDWYN, first released in July 1994."
            val line = draft(SourceId.MUSICBRAINZ, Scope.RELEASE, text)
            assertTrue(nothingBeyond(listOf(line)), type)
        }
        val other = draft(SourceId.MUSICBRAINZ, Scope.RELEASE, "“Demos” is a live album by $EDWYN.")
        assertFalse(nothingBeyond(listOf(other)))
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
    fun `the player's single or EP, marked in any of its forms, is MusicBrainz's single or EP`() {
        val ep = musicBrainz(release = FirstRelease("2021-04-09", "Numb Nothing Days", "rg", "r", "EP"))
        // The playlist's track 56 marked its EP "(EP)", which no form matched before.
        for (named in listOf("Numb Nothing Days (EP)", "Numb Nothing Days [EP]", "Numb Nothing Days - EP")) {
            assertTrue(nothingBeyond(ep, playing.copy(album = named)), named)
        }
        assertFalse(nothingBeyond(ep, playing.copy(album = "Numb Nothing Days")), "an album is not the EP")
        assertFalse(nothingBeyond(ep, playing.copy(album = "Numb Nothing Days (Single)")), "nor is a single")
        for (named in listOf("The Campaign for Real Rock (Single)", "The Campaign for Real Rock [single]")) {
            assertTrue(nothingBeyond(musicBrainz(release = SINGLE), playing.copy(album = named)), named)
        }
        // An album the player marks as a single is another release.
        assertFalse(nothingBeyond(musicBrainz(), playing.copy(album = "Gorgeous George - Single")))
        val marked = checkNotNull(HomeShown.PlayerAlbum.of("Numb Nothing Days (Remastered) - EP"))
        assertEquals("numb nothing days" to "ep", marked.name to marked.kind)
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
                    credit(CreditRole.PERFORMER, "D"),
                    Credit(CreditRole.PERFORMER, "E", "e", listOf("guitar", "vocals")),
                ),
            ).map { it.text }
        // A performer's with its detail is more than a name (#1142).
        assertEquals(
            listOf(null, "A", "B", "C", "D", null),
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
