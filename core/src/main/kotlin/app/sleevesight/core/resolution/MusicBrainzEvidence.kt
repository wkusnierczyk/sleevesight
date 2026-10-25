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

    // The first-release line as [lines] writes it: the title quoted, an optional credit, the date as
    // `EvidenceText.date` words it, then the release's type, if any, and its title, quoted, to the end.
    // The title and credit are matched lazily, so the line is read at its first date and type; the
    // release's title is everything after them, whatever it holds.
    private val FIRST_RELEASE =
        Regex(
            "^\u201C.*?\u201D(?: by .*?)? was first released (?:in \\S+|in \\S+ \\S+|on \\S+ \\S+ \\S+), " +
                "on (the [a-z]+ )?\u201C(.*)\u201D\\.$",
        )

    private const val ALBUM = "the album "

    /**
     * The album a first-release line of [lines] names, as written: `Sundown` from "“Carefree
     * Highway” by Gordon Lightfoot was first released in 1974, on the album “Sundown”." Null when
     * [text] is no first-release line, or names a single, an EP or a release of no type: the release
     * is read from its own slot of the template, after the date and the type, never from the track's
     * title or the artist credit quoted before it, and whole, even when its title holds the
     * template's own words (#424, `PromptEvidence.restatesRelease`).
     */
    fun firstReleaseAlbum(text: String): String? =
        FIRST_RELEASE
            .find(text)
            ?.takeIf { it.groupValues[1] == ALBUM }
            ?.groupValues
            ?.get(2)
            ?.takeIf { it.isNotEmpty() }

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
