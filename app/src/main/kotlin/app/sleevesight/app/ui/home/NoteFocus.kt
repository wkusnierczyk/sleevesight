package app.sleevesight.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.sleevesight.app.R
import kotlinx.coroutines.delay

// The mark for text the sources do not back (#954, #1437): a dimmed sentence's look, the `?` chip after
// the last of a run of them citing nothing, the note under the reply, worded for what is dimmed, and
// the way a `?` chip or a grey line chip goes to it, as a citation's chip goes to its line.

/**
 * The way to a reply's note on its dimmed text (#954): a `?` chip tapped ([go]) brings the note into
 * view ([requester]) and flashes it, as a citation's chip does its line ([LineFocus]): a request
 * ([request]) pending until the note has flashed ([flashed]), so a note composed later, for another
 * reply, does not flash untapped. Each tap is a new request, so the same chip tapped again flashes
 * again.
 */
@Stable
internal class NoteFocus {
    /** The note's way into view. */
    val requester: BringIntoViewRequester = BringIntoViewRequester()

    /** The tap the note is to flash for, until it has, or null. */
    var request: Int? by mutableStateOf(null)
        private set

    private var taps = 0

    /** Brings the note into view and flashes it. */
    fun go() {
        request = ++taps
    }

    /** The note has flashed for [done] (or stopped while it did): no longer pending, unless tapped since. */
    fun flashed(done: Int) {
        if (request == done) request = null
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
    val request = focus.request
    LaunchedEffect(focus, request) {
        if (request == null) return@LaunchedEffect
        // Flashing from the tap, as a citation's line is, while it scrolls into view.
        flashing = true
        try {
            withFrameNanos { }
            focus.requester.bringIntoView()
            delay(FLASH)
        } finally {
            flashing = false
            focus.flashed(request)
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
 * The note under a reply with dimmed text (#1437, after #954), worded for what is dimmed ([unbacked]):
 * text citing lines that do not back it ("Dimmed text is not fully backed by the cited sources S2
 * S8."), text citing nothing ("Dimmed text is not backed by any source."), or both, in the notes'
 * small muted type. The lines' chips are grey, as those lines' chips in the text
 * ([CiteStyles.unsupported], #1458), each opening *Sources* at its line ([NoteCites.onCite]); a screen
 * reader hears them as "(Source S2, Source S8)" and has each, up to [MAX_LINE_ACTIONS], as an action.
 * The grey line chips and the `?` chips of the text go to it ([noteTarget]).
 */
@Composable
internal fun UnbackedNote(
    unbacked: Unbacked,
    modifier: Modifier = Modifier,
    onCite: (String) -> Unit = {},
) {
    val resources = LocalResources.current
    val styles = citeStyles()
    val words =
        when {
            unbacked.lines.isEmpty() -> R.string.home_note_unbacked_uncited
            unbacked.uncited -> R.string.home_note_unbacked_both
            else -> R.string.home_note_unbacked_unsupported
        }
    val text =
        buildAnnotatedString {
            val parts = resources.getString(words, CHIPS_AT).split(CHIPS_AT, limit = 2)
            append(parts.first())
            unbacked.lines.forEachIndexed { i, id ->
                if (i > 0) append(' ')
                appendCite(id, id, CiteKind.LINE, styles, onCite, unsupported = true)
            }
            parts.getOrNull(1)?.let(::append)
        }
    val cited = unbacked.lines.map { resources.getString(R.string.home_cite, it) }
    val said = resources.getString(words, cited.joinToString(", ", "(", ")"))
    val exported = withoutIsolates(text)
    Text(
        text,
        modifier =
            modifier.noteTarget().semantics {
                this.text = exported
                contentDescription = said
                // Each line's chip as an action, at most [MAX_LINE_ACTIONS], as a paragraph's: Compose
                // allows no more than 32 on one node, and *Sources* lists every line.
                if (unbacked.lines.isNotEmpty()) {
                    customActions =
                        unbacked.lines.take(MAX_LINE_ACTIONS).zip(cited) { id, name ->
                            CustomAccessibilityAction(name) {
                                onCite(id)
                                true
                            }
                        }
                }
            },
        style = citedTextStyle(MaterialTheme.typography.labelMedium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Where the note's words take the lines' chips: a character no text holds.
private const val CHIPS_AT = "\uE000"

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
 * [runs] of the last sentence of a run of dimmed ones citing lines that do not back them (#1437):
 * its citations taken out where they were ([withoutChips]), inline or repeated, and shown once each,
 * in the order they first come, as one group at its end, before its final punctuation, where the `?`
 * chip goes ([unbackedAt]).
 */
internal fun chipsAtEnd(runs: List<ShownRun>): List<ShownRun> {
    val chips = runs.filterIsInstance<ShownRun.Cite>().distinct()
    if (chips.isEmpty()) return runs
    val (before, after) = unbackedAt(withoutChips(runs))
    val last = (before.lastOrNull() as? ShownRun.Text)?.text
    val gap = listOfNotNull(ShownRun.Text(" ").takeIf { last != null && last.lastOrNull()?.isWhitespace() != true })
    return before + gap + chips + after
}

/**
 * What a screen reader says of a checked sentence that cites nothing, the last of its run, whose `?`
 * chip it hears (#954, #1437): [words] with "(not cited)" before its final punctuation, where a
 * citation would be: "It is great (not cited)." A closing quote keeps it out of the quotation:
 * 'They called it "great." (not cited)'. A closing bracket alone keeps it inside, as a citation
 * would be: "(See the sleeve (not cited).)"; a closing quote among the closers still keeps it out:
 * '(They called it "great.") (not cited)'.
 */
internal fun notCited(
    words: String,
    uncited: String,
): String {
    val end = FINAL_PUNCTUATION.find(words)
    val at = if (end == null || end.groupValues[1].any { it in QUOTES }) words.length else end.range.first
    return words.take(at) + " ($uncited)" + words.drop(at)
}

// A dimmed sentence's opacity, of the text's colour (#954), and the `?` chip's grey's, of the text's.
private const val DIMMED_ALPHA = 0.6f
internal const val UNBACKED_GREY = 0.1f

/** The `?` chip's text, its thin spaces the chip's padding, as a citation chip's. */
internal const val UNBACKED_LABEL: String = "\u2009?\u2009"

/** The test tags of the note on dimmed text (#954), and of it while it flashes. */
const val UNBACKED_NOTE_TAG: String = "unbacked-note"
const val UNBACKED_NOTE_FLASHING_TAG: String = "unbacked-note:flashing"
