package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.synthesis.SentenceStatus
import kotlinx.coroutines.delay

// The mark for text the sources do not back (#954): a dimmed sentence's look, the `?` chip after the
// last of a run of them, the note under the reply ("? Dimmed text is not backed by the sources."), and
// the way a `?` chip goes to it, as a citation's chip goes to its line.

/**
 * The way to a reply's note on its dimmed text (#954): a `?` chip tapped ([go]) brings the note into
 * view ([requester]) and flashes it, as a citation's chip does its line ([LineFocus]). Each tap
 * counts ([taps]), so the same chip tapped again flashes again.
 */
@Stable
internal class NoteFocus {
    /** The note's way into view. */
    val requester: BringIntoViewRequester = BringIntoViewRequester()

    /** The `?` chips tapped so far; the note flashes on each. */
    var taps: Int by mutableIntStateOf(0)
        private set

    /** Brings the note into view and flashes it. */
    fun go() {
        taps++
    }
}

/**
 * The [NoteFocus] of the reply being shown: given by [WithUnbackedNote] around a reply and its note,
 * null outside one, where a `?` chip still shows but goes nowhere.
 */
internal val LocalNoteFocus = staticCompositionLocalOf<NoteFocus?> { null }

/** [content], a reply and its note, sharing one way from its `?` chips to the note ([NoteFocus]). */
@Composable
internal fun WithUnbackedNote(content: @Composable () -> Unit) {
    val focus = remember { NoteFocus() }
    CompositionLocalProvider(LocalNoteFocus provides focus, content = content)
}

/**
 * [modifier] with the way into view of the reply's note and its flash (#954): a `?` chip tapped
 * brings it into view and, for [FLASH], fills it with the colour a citation's line flashes in.
 */
@Composable
internal fun Modifier.noteTarget(): Modifier {
    val focus = LocalNoteFocus.current ?: return this
    var flashing by remember { mutableStateOf(false) }
    val taps = focus.taps
    LaunchedEffect(focus, taps) {
        if (taps == 0) return@LaunchedEffect
        // Flashing from the tap, as a citation's line is, while it scrolls into view.
        flashing = true
        try {
            withFrameNanos { }
            focus.requester.bringIntoView()
            delay(FLASH)
        } finally {
            flashing = false
        }
    }
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (flashing) colors.tertiaryContainer else Color.Transparent,
        label = "note flash",
    )
    return this
        .bringIntoViewRequester(focus.requester)
        .background(background)
        .testTag(if (flashing) UNBACKED_NOTE_FLASHING_TAG else UNBACKED_NOTE_TAG)
}

/**
 * The note under a reply with dimmed text (#954): the `?` chip the dimmed sentences end with, then
 * "Dimmed text is not backed by the sources.", in the notes' small muted type, on their shared
 * baseline, the words wrapping beside the chip. A screen reader hears the words alone; the chip here
 * is the one the sentences' chips point at, not a link.
 */
@Composable
internal fun UnbackedNote(modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chip = citeStyles().unbacked
    Row(modifier.noteTarget()) {
        Text(
            buildAnnotatedString { withStyle(chip) { append(UNBACKED_LABEL) } },
            modifier = Modifier.alignByBaseline().clearAndSetSemantics { },
            style = style,
            color = muted,
        )
        Text(
            stringResource(R.string.home_note_unbacked),
            modifier = Modifier.padding(start = NOTE_CHIP_GAP).alignByBaseline(),
            style = style,
            color = muted,
        )
    }
}

// The space between the note's `?` chip and its words, as a word space.
private val NOTE_CHIP_GAP = 4.dp

/**
 * A dimmed sentence's look (#954): upright, in the text's own colour at [DIMMED_ALPHA], nothing behind
 * or under it, so it follows the theme and dims the same on the page and in a panel.
 */
@Composable
internal fun dimmedText(): SpanStyle {
    val ink = LocalContentColor.current.takeIf { it.isSpecified } ?: MaterialTheme.colorScheme.onSurface
    return SpanStyle(color = ink.copy(alpha = ink.alpha * DIMMED_ALPHA))
}

/**
 * Which of a paragraph's sentences, [dimmed] or not, end with a `?` chip (#954): the last of each run
 * of dimmed sentences, a sentence not dimmed or the paragraph's end ending a run.
 */
internal fun runEnds(dimmed: List<Boolean>): List<Boolean> =
    dimmed.mapIndexed { i, dim -> dim && dimmed.getOrNull(i + 1) != true }

/**
 * Where a dimmed sentence's `?` chip goes among its [runs] (#954), as the runs before it and after it:
 * after its own chips, before its final punctuation (and any closing brackets), where a citation would
 * be; after a closing quote, or with no final punctuation in its plain text, at its end.
 */
internal fun unbackedAt(runs: List<ShownRun>): Pair<List<ShownRun>, List<ShownRun>> {
    val last = runs.lastOrNull() as? ShownRun.Text
    val plain = last != null && !last.italic && !last.bold && !last.code
    val end = if (plain) FINAL_PUNCTUATION.find(last.text) else null
    if (last == null || end == null || end.groupValues[1].any { it in QUOTES }) return runs to emptyList()
    val before = last.text.substring(0, end.range.first)
    val head = runs.dropLast(1) + listOfNotNull(ShownRun.Text(before).takeIf { before.isNotEmpty() })
    return head to listOf(ShownRun.Text(last.text.substring(end.range.first)))
}

/**
 * What a screen reader says of a checked sentence the sources do not back, beside its words: "(not
 * cited)" or "(not backed by its source)", then, for the last of a run of dimmed sentences, its `?`
 * chip's "(Not backed, see the note)" (#954).
 */
internal class SpokenNotes(
    resources: Resources,
) {
    private val uncited = resources.getString(R.string.home_cite_none)
    private val unbacked = resources.getString(R.string.home_cite_unbacked)
    private val seeNote = resources.getString(R.string.home_cite_see_note)

    /**
     * [words], a sentence of [status], as said: its notes before its final punctuation, where a
     * citation would be: "It is great (not cited)." A closing quote keeps them out of the quotation:
     * 'They called it "great." (not cited)'. A closing bracket alone keeps them inside, as a citation
     * would be: "(See the sleeve (not cited).)"; a closing quote among the closers still keeps them
     * out: '(They called it "great.") (not cited)'. With [chip], its `?` chip's words follow.
     */
    fun said(
        words: String,
        status: SentenceStatus,
        chip: Boolean,
    ): String {
        val end = FINAL_PUNCTUATION.find(words)
        val at = if (end == null || end.groupValues[1].any { it in QUOTES }) words.length else end.range.first
        val note =
            when (status) {
                SentenceStatus.UNCITED -> " ($uncited)"
                SentenceStatus.UNSUPPORTED -> " ($unbacked)"
                else -> ""
            }
        val said = note + if (chip) " ($seeNote)" else ""
        return if (said.isEmpty()) words else words.take(at) + said + words.drop(at)
    }
}

// A dimmed sentence's opacity, of the text's colour (#954), and the `?` chip's grey's, of the text's.
private const val DIMMED_ALPHA = 0.6f
internal const val UNBACKED_GREY = 0.1f

/** The `?` chip's text, its thin spaces the chip's padding, as a citation chip's. */
internal const val UNBACKED_LABEL: String = "\u2009?\u2009"

/** The test tags of the note on dimmed text (#954), and of it while it flashes. */
const val UNBACKED_NOTE_TAG: String = "unbacked-note"
const val UNBACKED_NOTE_FLASHING_TAG: String = "unbacked-note:flashing"
