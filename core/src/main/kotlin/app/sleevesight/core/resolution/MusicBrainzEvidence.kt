package app.sleevesight.core.resolution

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.Resolution
import kotlin.time.Instant

/**
 * MusicBrainz's evidence lines, templated from the [Resolution] (D1; `design/architecture.md`,
 * *Source adapters*): first release, cover and original recording, writers, producers and
 * performers, one claim per line, each linking the MusicBrainz page it rests on. MusicBrainz core
 * data is CC0; tags and genres never enter a line.
 *
 * The lines come in a fixed order for the same resolution, as evidence ids require.
 */
object MusicBrainzEvidence {
    /** At most this many performer lines: an orchestra's recording can credit dozens. */
    const val MAX_PERFORMER_LINES: Int = 6

    private const val SITE = "https://musicbrainz.org"

    // The first-release line's end when the release is an album, as [lines] writes it: `, on the album “…”.`
    private const val ON_THE_ALBUM = ", on the album \u201C"
    private const val CLOSING = "\u201D."
    private const val FIRST_RELEASED = " was first released "

    /**
     * The album a first-release line of [lines] names, as written: `Sundown` from "“Carefree
     * Highway” by Gordon Lightfoot was first released in 1974, on the album “Sundown”." Null when
     * [text] is no first-release line, or names a single, an EP or a release of no type: the release
     * is read from its own slot of the template, never from the track's title or the artist credit
     * quoted before it (#424, `PromptEvidence.restatesRelease`).
     */
    fun firstReleaseAlbum(text: String): String? {
        val at = text.lastIndexOf(ON_THE_ALBUM)
        val start = at + ON_THE_ALBUM.length
        val end = text.length - CLOSING.length
        val written = FIRST_RELEASED in text && text.endsWith(CLOSING) && at >= 0 && start < end
        return if (written) text.substring(start, end) else null
    }

    /** The lines for [resolution], dated [retrievedAt]; none when no recording was accepted. */
    fun lines(
        resolution: Resolution,
        retrievedAt: Instant,
    ): List<EvidenceDraft> {
        val recording = resolution.recordingMbid
        val title = resolution.title
        if (recording == null || title == null) return emptyList()
        val by =
            resolution.artistCredit
                ?.takeIf { it.isNotBlank() }
                ?.let { " by $it" }
                .orEmpty()
        val lines = mutableListOf<Line>()
        resolution.firstRelease?.let { release ->
            val type =
                release.type
                    ?.lowercase()
                    ?.let { "the $it " }
                    .orEmpty()
            val link =
                release.releaseGroupMbid?.let { "$SITE/release-group/$it" } ?: "$SITE/release/${release.releaseMbid}"
            lines +=
                Line(
                    Scope.SONG,
                    "“$title”$by was first released ${EvidenceText.date(release.date)}, on $type“${release.title}”.",
                    link,
                )
        }
        val work = resolution.work
        if (work != null && resolution.cover) {
            lines +=
                Line(
                    Scope.SONG,
                    "This recording of “$title”$by is a cover of “${work.title}”.",
                    "$SITE/recording/$recording",
                )
            resolution.original?.let { original ->
                lines +=
                    Line(
                        Scope.ORIGINAL_SONG,
                        "The earliest dated recording of “${work.title}” on MusicBrainz is by " +
                            "${original.artistCredit}, ${EvidenceText.date(original.date)}.",
                        "$SITE/recording/${original.recordingMbid}",
                    )
            }
        }
        if (work != null) lines += writerLines(work.title, resolution.credits, "$SITE/work/${work.mbid}")
        lines += recordingLines(title, resolution.credits, "$SITE/recording/$recording")
        return lines.map { EvidenceDraft(SourceId.MUSICBRAINZ, it.scope, it.text, it.url, retrievedAt) }
    }

    private fun writerLines(
        title: String,
        credits: List<Credit>,
        url: String,
    ): List<Line> {
        // MusicBrainz's special-purpose artists, `[traditional]`, `[unknown]` and the like, are not people.
        fun named(role: CreditRole) = credits.filter { it.role == role && !it.name.startsWith("[") }.map { it.name }
        val traditional =
            credits.any {
                it.role != CreditRole.PRODUCER &&
                    it.role != CreditRole.PERFORMER &&
                    it.name == "[traditional]"
            }
        val writers = named(CreditRole.WRITER)
        val composers = named(CreditRole.COMPOSER)
        val lyricists = named(CreditRole.LYRICIST)
        val lines = mutableListOf<Line>()
        if (traditional) lines += Line(Scope.SONG, "“$title” is a traditional song.", url)
        if (composers.isNotEmpty() && composers == lyricists) {
            lines += Line(Scope.SONG, "“$title” was written by ${EvidenceText.list(composers)}.", url)
        } else {
            if (composers.isNotEmpty()) {
                lines +=
                    Line(Scope.SONG, "The music of “$title” was composed by ${EvidenceText.list(composers)}.", url)
            }
            if (lyricists.isNotEmpty()) {
                lines +=
                    Line(Scope.SONG, "The lyrics of “$title” were written by ${EvidenceText.list(lyricists)}.", url)
            }
        }
        if (writers.isNotEmpty()) {
            lines +=
                Line(Scope.SONG, "“$title” was written by ${EvidenceText.list(writers)}.", url)
        }
        return lines
    }

    private fun recordingLines(
        title: String,
        credits: List<Credit>,
        url: String,
    ): List<Line> {
        val lines = mutableListOf<Line>()
        val producers = credits.filter { it.role == CreditRole.PRODUCER }.map { it.name }
        if (producers.isNotEmpty()) {
            lines +=
                Line(Scope.SONG, "This recording of “$title” was produced by ${EvidenceText.list(producers)}.", url)
        }
        credits.filter { it.role == CreditRole.PERFORMER }.take(MAX_PERFORMER_LINES).forEach { performer ->
            val detail = if (performer.detail.isEmpty()) "" else " (${performer.detail.joinToString(", ")})"
            lines += Line(Scope.SONG, "${performer.name} performs on this recording of “$title”$detail.", url)
        }
        return lines
    }

    private data class Line(
        val scope: Scope,
        val text: String,
        val url: String,
    )
}
