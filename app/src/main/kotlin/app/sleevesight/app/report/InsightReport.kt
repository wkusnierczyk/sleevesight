package app.sleevesight.app.report

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.synthesis.InsightPrompt
import kotlin.time.Duration

/**
 * The report of a problem with [insight], written for [track], for the developer (#390): the
 * [reason] and the user's [note]; the track as the player reported it (title, artists, album,
 * length) and the MusicBrainz ids resolved for it; the insight's text, and the evidence lines its
 * prompt held, each with its source, scope and address; and the prompt version, AI service, model
 * and effort it was written with. Nothing else: never a key, the history, the settings, the player
 * or anything about the phone. In English, as the developer reads it, whatever the phone's
 * language; addressed to [Mail.SUPPORT].
 */
fun insightReport(
    track: TrackState,
    insight: InsightState.Written,
    reason: ReportReason,
    note: String,
): Mail {
    val playing = track.nowPlaying
    val body =
        buildString {
            appendLine("A problem with an insight, reported from Sleevesight.")
            appendLine()
            appendLine("Reason: ${reason.label}")
            appendLine("Note: ${note.trim().ifEmpty { NONE }}")
            appendLine()
            appendLine("Track, as the player reported it")
            appendLine("Title: ${playing.title}")
            appendLine("Artists: ${playing.artists}")
            appendLine("Album: ${playing.album ?: NOT_REPORTED}")
            appendLine("Length: ${playing.duration?.let(::length) ?: NOT_REPORTED}")
            appendLine()
            appendMusicBrainz(track)
            appendLine()
            appendLine("Written with")
            val by = insight.writtenBy
            appendLine("Prompt version: ${by?.promptVersion ?: UNKNOWN}")
            appendLine("AI service: ${by?.provider?.let(::providerName) ?: UNKNOWN}")
            appendLine("Model: ${by?.model ?: UNKNOWN}")
            appendLine("Reasoning effort: ${by?.let { effortName(it.effort) } ?: UNKNOWN}")
            appendLine()
            appendLine("Insight")
            appendLine(insight.grounded.text.trim())
            appendLine()
            appendEvidence(track, insight)
        }.trimEnd() + "\n"
    return Mail(
        to = Mail.SUPPORT,
        subject = "Sleevesight report: ${reason.label}: ${playing.title} by ${playing.artists}",
        body = body,
    )
}

// The MusicBrainz ids the run resolved. With no resolution, the artists' ids MusicBrainz's result
// holds, if it has one: the run makes none when every source's findings were kept from before, which
// it then says; it has none either when MusicBrainz is off, or the resolution failed.
internal fun StringBuilder.appendMusicBrainz(track: TrackState) {
    appendLine("MusicBrainz ids")
    val resolution = track.resolution
    if (resolution == null) {
        val allKept =
            track.sources.isNotEmpty() && track.sources.values.all { (it as? SourceState.Done)?.cached == true }
        appendLine(if (allKept) ALL_KEPT else NONE_RESOLVED)
        val kept = (track.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result?.labelArtists.orEmpty()
        appendArtists(kept)
        return
    }
    appendLine("Recording: ${resolution.recordingMbid ?: NOT_RESOLVED}")
    appendArtists(resolution.artists)
    appendResolved(resolution)
}

private fun StringBuilder.appendArtists(artists: List<ResolvedArtist>) {
    for (artist in artists) appendLine("Artist: ${artist.name}: ${artist.mbid ?: NOT_RESOLVED}")
}

private fun StringBuilder.appendResolved(resolution: Resolution) {
    resolution.work?.let { appendLine("Work: ${it.mbid}") }
    resolution.firstRelease?.let { first ->
        first.releaseGroupMbid?.let { appendLine("First release group: $it") }
        appendLine("First release: ${first.releaseMbid}")
    }
    resolution.original?.let { appendLine("Original recording: ${it.recordingMbid}") }
}

// The lines the insight's prompt held ([InsightState.Written.held]; all of them when null), as the prompt gave
// them, each with its address. An insight served from the cache keeps no record of what its prompt
// held, which may have left out some of the artist's lines (#239), so its report says so and lists
// all the track's lines.
private fun StringBuilder.appendEvidence(
    track: TrackState,
    insight: InsightState.Written,
) {
    val held = insight.held
    appendLine(
        if (insight.cached && held == null) {
            "Evidence: every line of the track. The insight was kept from before, and the prompt that wrote it " +
                "may have held fewer."
        } else {
            "Evidence the prompt held"
        },
    )
    val lines =
        track.evidence
            ?.lines
            .orEmpty()
            .filter { held == null || it.id in held }
    if (lines.isEmpty()) appendLine(NONE)
    for (line in lines) {
        appendLine(InsightPrompt.evidenceLine(line))
        line.url?.let { appendLine("  $it") }
    }
}

internal fun length(duration: Duration): String {
    val seconds = duration.inWholeSeconds
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

internal fun providerName(provider: ProviderId): String =
    when (provider) {
        ProviderId.ANTHROPIC -> "Anthropic"
        ProviderId.OPENAI -> "OpenAI"
        ProviderId.GOOGLE -> "Google"
        ProviderId.OLLAMA -> "Ollama"
    }

internal fun effortName(effort: ReasoningEffort?): String = effort?.name?.lowercase() ?: "the service's default"

private const val NONE = "none"
private const val NOT_REPORTED = "not reported"
private const val NOT_RESOLVED = "not resolved"
private const val NONE_RESOLVED = "None resolved for this track."
private const val ALL_KEPT = "None resolved in this run: the sources' findings were kept from before."
private const val UNKNOWN = "not known"
private const val SECONDS_PER_MINUTE = 60
