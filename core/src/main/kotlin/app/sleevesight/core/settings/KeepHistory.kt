package app.sleevesight.core.settings

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Whether what was written at [writtenAt] is past *Keep history for* ([lifetime],
 * [Settings.historyLifetime], null for *Forever*) at [now]: an insight or a genre's reason, which is
 * kept with the track heard (#523, #643). The one rule the synthesis cache serves and purges by, and
 * the app checks what it shows against, so an insight or reason past it is neither kept nor shown.
 * A time not known ([writtenAt] null) is never past it.
 */
fun pastKeepHistory(
    writtenAt: Instant?,
    lifetime: Duration?,
    now: Instant,
): Boolean = writtenAt != null && lifetime != null && now - writtenAt >= lifetime
