package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Crossfade
import java.text.NumberFormat
import kotlin.time.Duration

// *Crossfade*'s value as shown and as read (D5, #1365).

/**
 * *Crossfade* as shown: *Autodetect*, *5 s*, or, for one stored in a fraction of a second, which
 * skip detection uses as it is, that fraction (*7.6 s*, #1365).
 */
@Composable
internal fun crossfadeLabel(crossfade: Crossfade): String =
    when (crossfade) {
        Crossfade.Autodetect -> stringResource(R.string.settings_crossfade_auto)
        is Crossfade.Fixed ->
            fractionOfSeconds(crossfade.value)?.let { stringResource(R.string.settings_crossfade_seconds_fraction, it) }
                ?: stringResource(R.string.settings_crossfade_seconds, crossfade.value.inWholeSeconds.toInt())
    }

/** *Crossfade* in words, as a screen reader reads it: *Autodetect*, *5 seconds*, *7.6 seconds*. */
@Composable
internal fun crossfadeSpoken(crossfade: Crossfade): String =
    when (crossfade) {
        Crossfade.Autodetect -> crossfadeLabel(crossfade)
        is Crossfade.Fixed -> {
            val fraction = fractionOfSeconds(crossfade.value)
            val whole = crossfade.value.inWholeSeconds.toInt()
            if (fraction != null) {
                stringResource(R.string.settings_crossfade_seconds_fraction_spoken, fraction)
            } else {
                pluralStringResource(R.plurals.settings_seconds, whole, whole)
            }
        }
    }

/** The milliseconds in a second, the most precision a crossfade is stored with. */
private const val MILLIS = 1000

/** The digits of a second's fraction down to milliseconds. */
private const val MILLIS_DIGITS = 3

/**
 * [duration] in seconds with their fraction, formatted for the screen's language (*7.6*, *7,6*),
 * or null where it is a whole number of seconds.
 */
@Composable
private fun fractionOfSeconds(duration: Duration): String? {
    val millis = duration.inWholeMilliseconds
    if (millis % MILLIS == 0L) return null
    val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0])
    format.maximumFractionDigits = MILLIS_DIGITS
    return format.format(millis.toDouble() / MILLIS)
}
