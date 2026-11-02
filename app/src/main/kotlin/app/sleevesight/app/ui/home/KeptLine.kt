package app.sleevesight.app.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.sleevesight.app.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
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
 *
 * Read again, by the clock and in the time zone [LocalWallClock] gives, every minute while it is
 * shown (so a line left on screen past midnight no longer says today) and when the time, the date,
 * the time zone or the 24-hour setting changes (`ACTION_TIME_CHANGED` also comes for the last).
 */
@Composable
internal fun KeptLine(writtenAt: Instant) {
    val context = LocalContext.current
    val wall = LocalWallClock.current
    val locale = LocalConfiguration.current.locales[0]
    var changes by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    changes++
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        // Protected broadcasts, which only the platform may send. Exported, as `BleKeyboard`'s: before
        // Android 13 `ContextCompat` guards a receiver that is not with a permission of this app's.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val written =
        remember(writtenAt, wall, locale, changes) {
            writtenWhen(writtenAt, wall.clock.now(), wall.zone(), locale, DateFormat.is24HourFormat(context))
        }
    val text =
        when (written) {
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

/** The clock and the time zone the kept line reads (#851): the phone's, or a test's. */
internal class WallClock(
    val clock: Clock,
    val zone: () -> ZoneId,
) {
    companion object {
        val SYSTEM: WallClock = WallClock(Clock.System) { ZoneId.systemDefault() }
    }
}

/** The [WallClock] of the kept lines below it: the phone's, unless a test gives its own. */
internal val LocalWallClock = staticCompositionLocalOf { WallClock.SYSTEM }

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
 * ([hours24]); else its day and month, and its year when it is not [now]'s; in [locale]'s digits.
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
        DateTimeFormatter
            .ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .withDecimalStyle(DecimalStyle.of(locale))
            .format(written)
    return when {
        written.toLocalDate() == today.toLocalDate() -> Written.Today(format(if (hours24) "Hm" else "hm"))
        written.year == today.year -> Written.OnDay(format("dMMMM"))
        else -> Written.OnDay(format("dMMMMy"))
    }
}

/** The test tag of the line under an AI answer kept from before (#851). */
const val AI_KEPT_TAG: String = "ai-kept"
