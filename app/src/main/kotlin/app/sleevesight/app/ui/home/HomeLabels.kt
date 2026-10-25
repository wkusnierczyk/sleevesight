package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import kotlin.time.Duration

// The words the home screen puts to what it shows.

/** A player's state, as the player row says it; null for none, or a passing state. */
@Composable
internal fun stateText(status: PlaybackStatus): String? =
    when (status) {
        PlaybackStatus.PLAYING -> stringResource(R.string.home_state_playing)
        PlaybackStatus.PAUSED -> stringResource(R.string.home_state_paused)
        PlaybackStatus.STOPPED -> stringResource(R.string.home_state_stopped)
        PlaybackStatus.BUFFERING -> stringResource(R.string.home_state_buffering)
        PlaybackStatus.ERROR -> stringResource(R.string.home_state_error)
        PlaybackStatus.NONE, PlaybackStatus.OTHER -> null
    }

/** What a source's chip says of it. */
@Composable
internal fun statusText(status: ChipStatus): String =
    when (status) {
        ChipStatus.Release -> stringResource(R.string.home_source_release)
        ChipStatus.Pending -> stringResource(R.string.home_source_pending)
        ChipStatus.Failed -> stringResource(R.string.home_source_failed)
        ChipStatus.NeedsKey -> stringResource(R.string.home_source_needs_key)
        ChipStatus.ModelAllowed -> stringResource(R.string.home_source_model_allowed)
        is ChipStatus.Done -> {
            val outcome = outcomeText(status.outcome)
            if (status.cached) stringResource(R.string.home_source_cached, outcome) else outcome
        }
    }

@Composable
private fun outcomeText(outcome: Outcome): String =
    stringResource(
        when (outcome) {
            Outcome.SongArticle -> R.string.home_source_found_song
            Outcome.ArtistArticle -> R.string.home_source_found_artist
            Outcome.OriginalSong -> R.string.home_source_found_original
            is Outcome.Rejected -> R.string.home_source_rejected
            Outcome.None -> R.string.home_source_none
            Outcome.SourceOff -> R.string.home_source_off
            Outcome.Unreachable -> R.string.home_source_unreachable
        },
    )

/** What an evidence line is about. */
@Composable
internal fun scopeText(scope: Scope): String =
    stringResource(
        when (scope) {
            Scope.SONG -> R.string.home_scope_song
            Scope.ARTIST -> R.string.home_scope_artist
            Scope.ORIGINAL_SONG -> R.string.home_scope_original_song
            Scope.RELEASE -> R.string.home_scope_release
            Scope.WEB -> R.string.home_scope_web
        },
    )

/** A source's name, as the settings call it. */
@Composable
internal fun sourceName(source: SourceId): String =
    stringResource(
        when (source) {
            SourceId.MUSICBRAINZ -> R.string.settings_source_musicbrainz
            SourceId.WIKIPEDIA -> R.string.settings_source_wikipedia
            SourceId.WIKIDATA -> R.string.home_source_name_wikidata
            SourceId.LASTFM -> R.string.settings_source_lastfm
            SourceId.DISCOGS -> R.string.home_source_name_discogs
            SourceId.WEB -> R.string.home_source_name_web
            SourceId.MODEL -> R.string.settings_source_model
            SourceId.PLAYER -> R.string.home_source_name_player
        },
    )

/** The words for an insight's failure, from the fixed label the pipeline gives it (`InsightState.Failed.reason`). */
@Composable
internal fun failureText(reason: String): String =
    FAILURE_TEXTS[reason]?.let { stringResource(it) } ?: stringResource(R.string.home_failure_other, reason)

/** Each failure label's words: an `AiErrorKind`'s name in lower case, or the pipeline's own label. */
private val FAILURE_TEXTS: Map<String, Int> =
    mapOf(
        "offline" to R.string.home_failure_offline,
        "unauthorized" to R.string.home_failure_unauthorized,
        "billing" to R.string.home_failure_billing,
        "unavailable_here" to R.string.home_failure_unavailable_here,
        "quota_exhausted" to R.string.home_failure_quota_exhausted,
        "rate_limited" to R.string.home_failure_rate_limited,
        "overloaded" to R.string.home_failure_overloaded,
        "server" to R.string.home_failure_server,
        "model_not_found" to R.string.home_failure_model_not_found,
        "request_too_large" to R.string.home_failure_request_too_large,
        "incomplete" to R.string.home_failure_incomplete,
        "declined" to R.string.home_failure_declined,
        PipelineHost.FAILED to R.string.home_failure_failed,
    )

/** The host of [url], for an evidence line's link; null when it has none. */
internal fun hostOf(url: String?): String? =
    url
        ?.substringAfter("://", "")
        ?.substringBefore('/')
        ?.substringBefore('?')
        ?.substringAfter('@')
        ?.removePrefix("www.")
        ?.takeIf { it.isNotEmpty() }

/** `m:ss`, or `h:mm:ss` from an hour. */
internal fun clock(duration: Duration): String {
    val total = duration.inWholeSeconds
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = (total % SECONDS_PER_MINUTE).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
