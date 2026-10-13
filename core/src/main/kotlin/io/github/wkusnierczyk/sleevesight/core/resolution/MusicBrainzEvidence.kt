package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.sources.Credit
import io.github.wkusnierczyk.sleevesight.core.sources.CreditRole
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
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
                    "“$title”$by was first released ${dateText(release.date)}, on $type“${release.title}”.",
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
                        "“${work.title}” was first recorded by ${original.artistCredit}, ${dateText(original.date)}.",
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
            lines += Line(Scope.SONG, "“$title” was written by ${list(composers)}.", url)
        } else {
            if (composers.isNotEmpty()) {
                lines +=
                    Line(Scope.SONG, "The music of “$title” was composed by ${list(composers)}.", url)
            }
            if (lyricists.isNotEmpty()) {
                lines +=
                    Line(Scope.SONG, "The lyrics of “$title” were written by ${list(lyricists)}.", url)
            }
        }
        if (writers.isNotEmpty()) lines += Line(Scope.SONG, "“$title” was written by ${list(writers)}.", url)
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
                Line(Scope.SONG, "This recording of “$title” was produced by ${list(producers)}.", url)
        }
        credits.filter { it.role == CreditRole.PERFORMER }.take(MAX_PERFORMER_LINES).forEach { performer ->
            val detail = if (performer.detail.isEmpty()) "" else " (${performer.detail.joinToString(", ")})"
            lines += Line(Scope.SONG, "${performer.name} performs on this recording of “$title”$detail.", url)
        }
        return lines
    }

    /** `A`, `A and B`, `A, B and C`. */
    internal fun list(names: List<String>): String =
        when (names.size) {
            0 -> ""
            1 -> names.single()
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }

    /** A MusicBrainz date as prose: `in 1974`, `in January 1974`, `on 25 March 1974`. */
    internal fun dateText(date: String): String {
        val parts = date.split('-')
        val month = parts.getOrNull(1)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }
        val day = parts.getOrNull(2)?.toIntOrNull()
        return when {
            month == null -> "in ${parts[0]}"
            day == null -> "in $month ${parts[0]}"
            else -> "on $day $month ${parts[0]}"
        }
    }

    private val MONTHS =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )

    private data class Line(
        val scope: Scope,
        val text: String,
        val url: String,
    )
}
