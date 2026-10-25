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
            appendEvidence(track, insight.held)
        }.trimEnd() + "\n"
    return Mail(
        to = Mail.SUPPORT,
        subject = "Sleevesight report: ${reason.label}: ${playing.title} by ${playing.artists}",
        body = body,
    )
}

// The MusicBrainz ids the run resolved, or, for a track whose findings were all kept from before,
// which makes no resolution, the artists' ids MusicBrainz's kept result holds.
private fun StringBuilder.appendMusicBrainz(track: TrackState) {
    appendLine("MusicBrainz ids")
    val resolution = track.resolution
    if (resolution == null) {
        appendLine("Not resolved in this run: the sources' findings were kept from before.")
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

// The lines the insight's prompt held ([held]; all of them when null), as the prompt gave them,
// each with its address.
private fun StringBuilder.appendEvidence(
    track: TrackState,
    held: Set<String>?,
) {
    appendLine("Evidence")
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

private fun length(duration: Duration): String {
    val seconds = duration.inWholeSeconds
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private fun providerName(provider: ProviderId): String =
    when (provider) {
        ProviderId.ANTHROPIC -> "Anthropic"
        ProviderId.OPENAI -> "OpenAI"
        ProviderId.GOOGLE -> "Google"
        ProviderId.OLLAMA -> "Ollama"
    }

private fun effortName(effort: ReasoningEffort?): String = effort?.name?.lowercase() ?: "the service's default"

private const val NONE = "none"
private const val NOT_REPORTED = "not reported"
private const val NOT_RESOLVED = "not resolved"
private const val UNKNOWN = "not known"
private const val SECONDS_PER_MINUTE = 60
