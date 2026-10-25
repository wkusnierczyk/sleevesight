package app.sleevesight.core.resolution

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MusicBrainzEvidenceTest {
    private val at = Instant.parse("2026-10-14T00:00:00Z")

    @Test
    fun `dates read at the precision MusicBrainz gives`() {
        assertEquals("in 1974", EvidenceText.date("1974"))
        assertEquals("in January 1974", EvidenceText.date("1974-01"))
        assertEquals("on 6 November 1964", EvidenceText.date("1964-11-06"))
    }

    @Test
    fun `nothing resolved gives no lines`() {
        assertEquals(emptyList(), MusicBrainzEvidence.lines(Resolution.NONE, at))
    }

    @Test
    fun `a cover's lines keep the original song's facts in their own scope`() {
        val resolution =
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = "rec",
                title = "Song",
                artistCredit = "Cover Act",
                firstRelease = FirstRelease("1978-09-18", "Album", "group", "release", "Album"),
                work = ResolvedWork("work", "Song"),
                cover = true,
                original = OriginalRecording("first", "Song", emptyList(), "Original Act", "1975"),
                credits =
                    listOf(
                        Credit(CreditRole.COMPOSER, "Writer", "w"),
                        Credit(CreditRole.LYRICIST, "Writer", "w"),
                        Credit(CreditRole.PRODUCER, "Producer", "p"),
                        Credit(CreditRole.PERFORMER, "Player", "x", listOf("electric guitar")),
                    ),
            )
        val lines = MusicBrainzEvidence.lines(resolution, at)
        assertEquals(
            listOf(
                Scope.SONG to "“Song” by Cover Act was first released on 18 September 1978, on the album “Album”.",
                Scope.SONG to "This recording of “Song” by Cover Act is a cover of “Song”.",
                Scope.ORIGINAL_SONG to
                    "The earliest dated recording of “Song” on MusicBrainz is by Original Act, in 1975.",
                Scope.SONG to "“Song” was written by Writer.",
                Scope.SONG to "This recording of “Song” was produced by Producer.",
                Scope.SONG to "Player performs on this recording of “Song” (electric guitar).",
            ),
            lines.map { it.scope to it.text },
        )
        assertEquals(setOf(SourceId.MUSICBRAINZ), lines.map { it.source }.toSet())
        assertEquals("https://musicbrainz.org/release-group/group", lines.first().url)
        assertEquals("https://musicbrainz.org/recording/first", lines[2].url)
    }

    // #424: `PromptEvidence.restatesRelease` reads the album back out of the line written here.
    private fun firstLine(
        type: String?,
        release: String,
        title: String = "Sundown",
    ): String =
        MusicBrainzEvidence
            .lines(
                Resolution(
                    outcome = Outcome.SongArticle,
                    recordingMbid = "rec",
                    title = title,
                    artistCredit = "Gordon Lightfoot",
                    firstRelease = FirstRelease("1974-01", release, "group", "release", type),
                ),
                at,
            ).first()
            .text

    @Test
    fun `the first-release line gives back the album it names, and no other release`() {
        assertEquals("Sundown", MusicBrainzEvidence.firstReleaseAlbum(firstLine("Album", "Sundown")))
        assertEquals(
            "Greatest Hits, Vol. 2 \u201CLive\u201D",
            MusicBrainzEvidence.firstReleaseAlbum(firstLine("Album", "Greatest Hits, Vol. 2 \u201CLive\u201D")),
        )
        // A release title holding the template's own words is read whole, and by its own type.
        val tricky = "Notes, on the album \u201CSundown\u201D"
        assertEquals(tricky, MusicBrainzEvidence.firstReleaseAlbum(firstLine("Album", tricky)))
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(firstLine("Single", tricky)))
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(firstLine(null, tricky)))
        val titled = firstLine("Album", "Sundown", title = "Was first released in 1974, on")
        assertEquals("Sundown", MusicBrainzEvidence.firstReleaseAlbum(titled))
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(firstLine("Single", "Sundown")))
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(firstLine("EP", "Sundown")))
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(firstLine(null, "Sundown")))
        val writer = "\u201CSundown\u201D was written by Gordon Lightfoot."
        assertEquals(null, MusicBrainzEvidence.firstReleaseAlbum(writer))
    }
}
