package app.sleevesight.app.ui.settings

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.appendCode
import app.sleevesight.app.ui.theme.codeStyle

// The key settings' own screens (#446): *Load keys* ([LoadKeysScreen]) and *Purge keys*
// ([PurgeKeysScreen]), in place of their dialogs; their titles, *Load keys*' numbered (#765), their
// headings, panels and buttons.

/** How many of a key's last characters the key screens show. */
internal const val SHOWN_END = 4

/** A saved or read key as the key screens show it: its name, and its last characters, if it can be read. */
internal data class ShownKey(
    val id: SecretId,
    val ending: String?,
)

/** A key in a [KeyTable]: [key]. */
internal data class KeyRow(
    val key: ShownKey,
)

/** [key]'s last characters, as the key screens show them: "a1B2". */
internal fun endingOf(key: String): String = key.takeLast(SHOWN_END)

/**
 * The text [line] of a key, by its [name] (`%1$s`) and its [ending] (`%2$s`, from [endingOf]), the
 * ending marked as code ([codeStyle]) and read by a screen reader character by character (#542):
 * "Anthropic, ending `a1B2`". A [line] without the ending is that text alone.
 */
@Composable
internal fun keyLine(
    @StringRes line: Int,
    name: String,
    ending: String,
): AnnotatedString {
    val text = stringResource(line, name, ENDING_MARK)
    val code = codeStyle()
    return buildAnnotatedString {
        val at = text.indexOf(ENDING_MARK)
        if (at < 0) {
            append(text)
            return@buildAnnotatedString
        }
        append(text, 0, at)
        appendCode(ending, code)
        append(text, at + ENDING_MARK.length, text.length)
    }
}

/** Where [keyLine] puts the ending: a character no key's name has. */
private const val ENDING_MARK = "\uFFFC"

/**
 * A key screen: [title] at the top, after its [step]'s number, if any ([KeyScreenTitle], #765), then
 * [content], filling the screen. It is a window of its own, secure (`FLAG_SECURE`), so no screenshot
 * or screen recording shows it, as the key dialogs are. Back calls [onDismiss], except while
 * [busy]: a save or a deletion then ends as its result says. With no [title], [content] shows it
 * itself, as a guided scan does inside what scrolls, so that a short window leaves the camera's view
 * as much room as it can (#541).
 */
@Composable
internal fun KeyScreen(
    @StringRes title: Int?,
    busy: Boolean,
    onDismiss: () -> Unit,
    step: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                title?.let { KeyScreenTitle(it, step) }
                content()
            }
        }
    }
}

/**
 * A key screen's [title], which a screen reader reads as a heading. With a [step], *Load keys*'
 * numbered steps (#765), its number goes before it, in an outlined circle, in the quiet colour, at
 * [STEP_NUMBER_SCALE] of the title's size, as the API keys page draws its steps; a screen reader
 * reads the two as one heading, "Step 1: Encryption words".
 */
@Composable
internal fun KeyScreenTitle(
    @StringRes title: Int,
    step: Int? = null,
) {
    val style = MaterialTheme.typography.headlineMedium
    val name = stringResource(title)
    if (step == null) {
        Text(name, style = style, modifier = Modifier.semantics { heading() })
        return
    }
    val spoken = stringResource(R.string.load_keys_step, step, name)
    Row(
        modifier =
            Modifier.clearAndSetSemantics {
                heading()
                text = AnnotatedString(spoken)
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number's line as tall as its size, centred and trimmed: the title's line height would
        // make it taller than the circle, and the digit would sit low in it (#1012).
        val size = style.fontSize * STEP_NUMBER_SCALE
        val number =
            style.copy(
                fontSize = size,
                lineHeight = size,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                fontFeatureSettings = "tnum",
            )
        // The circle as wide as the number's size and a half again, as the page's.
        val side = with(LocalDensity.current) { (number.fontSize * STEP_CIRCLE_SCALE).toDp() }
        Box(
            modifier = Modifier.size(side).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Centred by its ink, not its text box: a digit's side bearings differ (Roboto's 1 sits
            // left of its box's middle), so the box's centre is not the glyph's (#1012).
            val digits = step.toString()
            val ink = inkCentre(digits, number)
            var shift by remember { mutableStateOf(Offset.Zero) }
            Text(
                digits,
                style = number,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { layout ->
                    shift = layout.size.toSize().center - Offset(ink.x, layout.firstBaseline + ink.y)
                },
                modifier =
                    Modifier.graphicsLayer {
                        translationX = shift.x
                        translationY = shift.y
                    },
            )
        }
        Text(name, style = style)
    }
}

/**
 * The centre of [text]'s ink in [style], in pixels from the start of its baseline: the glyphs'
 * outlines, which a text layout does not give (its paths are selection boxes).
 */
@Composable
private fun inkCentre(
    text: String,
    style: TextStyle,
): Offset {
    val density = LocalDensity.current
    // Read here, not in remember, so a font that finishes loading measures again.
    val typeface by
        LocalFontFamilyResolver.current.resolve(
            style.fontFamily,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
            style.fontSynthesis ?: FontSynthesis.All,
        )
    return remember(text, style, typeface, density) {
        val paint =
            Paint().apply {
                this.typeface = typeface as Typeface
                textSize = with(density) { style.fontSize.toPx() }
                fontFeatureSettings = style.fontFeatureSettings
            }
        val bounds = RectF()
        Path().apply { paint.getTextPath(text, 0, text.length, 0f, 0f, this) }.computeBounds(bounds, true)
        Offset(bounds.centerX(), bounds.centerY())
    }
}

/** How large a step's number is, against its title ([KeyScreenTitle]). */
private const val STEP_NUMBER_SCALE = 0.7f

/** How wide a step's circle is, against its number. */
private const val STEP_CIRCLE_SCALE = 1.4f

/**
 * A panel on a key screen (#765): filled with the surface container colour, 16 dp corners and
 * padding, its [content] 10 dp apart; *Load keys*' intro and summary are each one.
 */
@Composable
internal fun KeyPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * A step's heading on a key screen: a screen reader reads it as a heading, and, when [announced],
 * says it as soon as it shows, as a result that replaces what was there. When [busy], something is
 * under way, and the turning record goes before it on its line ([Busy], #785), which a screen
 * reader skips: the heading reads, and is announced, as it is, the same node with the record or
 * without.
 */
@Composable
internal fun StepHeading(
    text: String,
    announced: Boolean = false,
    busy: Boolean = false,
) {
    val style = MaterialTheme.typography.titleMedium
    val semantics =
        Modifier.semantics {
            heading()
            if (announced) liveRegion = LiveRegionMode.Polite
        }
    Busy(text, style, textModifier = semantics, record = busy)
}

/**
 * A key screen's buttons: [cancel], if any, at the start of the line, as in the key dialog (#541,
 * #543), and the others ([content]) at its end, wrapping where the screen is narrow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyScreenActions(
    cancel: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cancel?.invoke()
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) { content() }
    }
}
