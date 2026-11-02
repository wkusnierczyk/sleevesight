package app.sleevesight.app.ui.home

import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Under an AI answer kept from before (#851: one written before this play of the track, served from
 * what was kept), after its credit lines: "Kept from before: written today at 13:04." for one written
 * today, else "Kept from before: written on 31 October.", with the year when it is not this year; the
 * time and date as the phone's language and 24-hour setting write them. In the quiet style of the
 * grounding note, not the credit's italics. Read by a screen reader as shown.
 */
@Composable
internal fun KeptLine(
    writtenAt: Instant,
    now: Instant = Clock.System.now(),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val hours24 = DateFormat.is24HourFormat(context)
    val text =
        when (val written = writtenWhen(writtenAt, now, ZoneId.systemDefault(), locale, hours24)) {
            is Written.Today -> stringResource(R.string.home_ai_kept_today, written.time)
            is Written.OnDay -> stringResource(R.string.home_ai_kept_on, written.date)
        }
    Text(
        text,
        modifier = Modifier.testTag(AI_KEPT_TAG),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** When a kept answer was written, as [KeptLine] says it: today, at a [time], or on a [date]. */
internal sealed interface Written {
    data class Today(
        val time: String,
    ) : Written

    data class OnDay(
        val date: String,
    ) : Written
}

/**
 * When [writtenAt] was, seen at [now] in [zone]: today, its time in [locale]'s way, 24-hour or not
 * ([hours24]); else its day and month, and its year when it is not [now]'s.
 */
internal fun writtenWhen(
    writtenAt: Instant,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
    hours24: Boolean,
): Written {
    val written = writtenAt.toJavaInstant().atZone(zone)
    val today = now.toJavaInstant().atZone(zone)

    fun format(skeleton: String) =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(written)
    return when {
        written.toLocalDate() == today.toLocalDate() -> Written.Today(format(if (hours24) "Hm" else "hm"))
        written.year == today.year -> Written.OnDay(format("dMMMM"))
        else -> Written.OnDay(format("dMMMMy"))
    }
}

/** The test tag of the line under an AI answer kept from before (#851). */
const val AI_KEPT_TAG: String = "ai-kept"
