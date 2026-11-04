package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.scan.ScanFrame
import app.sleevesight.app.ui.theme.isAppInDarkTheme

// *Load keys*' ② (#765): the camera, and under it what became of the code it read.

/**
 * ② *QR Code* (#765), a guided scan ([GuidedScan]): its title, numbered, and how to scan the code;
 * the camera's view ([ScanCamera]), whose codes [onScanned] hears; under it, what became of the
 * code, as [scan] has it ([ScanOutcome]), and to tap *Next* once it is read. *Previous*
 * ([onPrevious]) goes back to the words, but not while a code opens; *Next* ([onNext]) is dimmed
 * until a code is read; *Cancel* calls [onCancel].
 */
@Suppress("LongParameterList") // What the camera read, and what each button does.
@Composable
internal fun ColumnScope.ScanStep(
    scan: ScanState,
    scanner: QrScanner,
    onScanned: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    GuidedScan(
        above = {
            KeyScreenTitle(R.string.load_keys_scan_title, step = 2)
            Text(stringResource(R.string.load_keys_scan_how), style = style)
        },
        camera = { ScanCamera(scan, scanner, onScanned) },
        below = {
            ScanOutcome(scan)
            Text(stringResource(R.string.load_keys_scan_next), style = style)
        },
        onCancel = onCancel,
    ) {
        TextButton(onClick = onPrevious, enabled = scan !is ScanState.Opening) {
            Text(stringResource(R.string.load_keys_previous))
        }
        TextButton(onClick = onNext, enabled = scan is ScanState.Read) { Text(stringResource(R.string.load_keys_next)) }
    }
}

/**
 * The camera's view on ②: [scanner]'s, reading codes for [onScanned], until one is read; then the
 * camera stops, and its place shows the frame's corners in the success colour ([READ_MARKS]). While
 * a sealed code opens, about a second on a slow phone, the turning record and *Opening the code…*
 * show over it ([StepHeading], #785), announced.
 */
@Composable
private fun ScanCamera(
    scan: ScanState,
    scanner: QrScanner,
    onScanned: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (scan is ScanState.Read) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                ScanFrame(Modifier.fillMaxSize(), color = READ_MARKS)
            }
        } else {
            scanner(onScanned)
        }
        if (scan is ScanState.Opening) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = OPENING_DIM)),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    StepHeading(stringResource(R.string.load_keys_opening), announced = true, busy = true)
                }
            }
        }
    }
}

/**
 * Under ②'s camera, what became of the code it read, as [scan] has it, the outcome only and never
 * any detail of the keys: read, in a panel of the success colour, and, for a code made without the
 * words (version 1), a warning under it that anyone who saw it can read the keys; refused, why, in
 * an error panel; or nothing, while the camera reads or a code opens.
 */
@Composable
private fun ScanOutcome(scan: ScanState) {
    when (scan) {
        is ScanState.Read -> {
            OutcomePanel(Outcome.READ, R.string.load_keys_read_title, R.string.load_keys_read_done)
            if (!scan.load.sealed) {
                OutcomePanel(Outcome.WARNING, R.string.load_keys_clear_title, R.string.load_keys_clear)
            }
        }

        is ScanState.Reading -> {
            scan.refused?.let { reason ->
                val (title, text) = refusalTexts(reason)
                OutcomePanel(Outcome.REFUSED, title, text)
            }
        }

        is ScanState.Opening -> {}
    }
}

/** The title and the text of the panel that says why a code was refused for [reason]. */
private fun refusalTexts(reason: Refusal): Pair<Int, Int> =
    when (reason) {
        Refusal.WRONG_WORDS -> R.string.load_keys_wrong_words_title to R.string.load_keys_wrong_words
        Refusal.EXPIRED -> R.string.load_keys_expired_title to R.string.load_keys_expired
        Refusal.USED -> R.string.load_keys_used_title to R.string.load_keys_used
        Refusal.DAMAGED -> R.string.load_keys_damaged_title to R.string.load_keys_damaged
        Refusal.NOT_A_SETUP -> R.string.load_keys_not_a_setup_title to R.string.load_keys_not_a_setup
        Refusal.NEWER -> R.string.load_keys_newer_title to R.string.load_keys_newer
    }

/** What a panel under ②'s camera says of a code: read, read with a warning, or refused. */
private enum class Outcome { READ, WARNING, REFUSED }

/**
 * A panel under ②'s camera, filled after its [outcome], with its [title] in medium weight (in the
 * success colour for a code read, the error colour for one refused) and its [text] under it. A
 * screen reader reads the two as one, and says them as soon as they show.
 */
@Composable
private fun OutcomePanel(
    outcome: Outcome,
    @StringRes title: Int,
    @StringRes text: Int,
) {
    val dark = isAppInDarkTheme()
    val (fill, ink) =
        when (outcome) {
            Outcome.READ -> (if (dark) READ_FILL_DARK else READ_FILL) to (if (dark) READ_INK_DARK else READ_INK)
            Outcome.WARNING -> (if (dark) WARNING_FILL_DARK else WARNING_FILL) to Color.Unspecified
            Outcome.REFUSED -> (if (dark) REFUSED_FILL_DARK else REFUSED_FILL) to MaterialTheme.colorScheme.error
        }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(fill, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        Text(stringResource(title), style = style, fontWeight = FontWeight.Medium, color = ink)
        Text(stringResource(text), style = style)
    }
}

/** How dark the camera's view is under *Opening the code…*. */
private const val OPENING_DIM = 0.55f

/** The frame's corners once a code is read, on the camera's dark place, in either theme. */
@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val READ_MARKS = Color(0xFF7FD49A)

// The panels' fills and the success colour, light and dark, as the mock-up gives them (#765).
@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val READ_FILL = Color(0xFFE5F3EA)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val READ_FILL_DARK = Color(0xFF1D3326)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val READ_INK = Color(0xFF1E6B3A)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val READ_INK_DARK = Color(0xFF9FD8B0)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val WARNING_FILL = Color(0xFFFFF4D6)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val WARNING_FILL_DARK = Color(0xFF3A3220)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val REFUSED_FILL = Color(0xFFFBE9E7)

@Suppress("MagicNumber") // A colour, as the mock-up gives it.
private val REFUSED_FILL_DARK = Color(0xFF3B1F1D)
