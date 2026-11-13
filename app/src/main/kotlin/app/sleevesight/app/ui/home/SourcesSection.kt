package app.sleevesight.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState

/**
 * The *Sources* section, quiet, at the bottom (design, *Home screen*). Folded, it is a chip,
 * *Sources* ([SourcesChip], #623), drawn as a genre chip is, and nothing else; a tap on it opens its
 * panel in its place ([Panel], as a genre's is): *Sources* as a heading, with *Close* at the top
 * right, which folds it back to the chip; the focus moves to *Close* on opening and back to the chip
 * on closing (a keyboard's, and a screen reader's), and to the chip when a new track folds the panel
 * while a keyboard's focus is in it. In the panel, a table of the sources ([SourceTable], #662), a
 * row for each but those that are off ([tableRows]): a chip with the letter its citations use, which
 * shows or hides that source's lines (a display filter only: what the model may use is the
 * settings'), dimmed for one that gave none, and its name and what it found; a note when a source
 * needs the listener's key, could not be reached or failed ([sourceNotes]), its key's linking to
 * the settings ([openSettings]); and the list: the evidence lines, each with its scope and a link
 * to where it was found, and the lines of matches that could not be verified, which are shown but
 * never cited. The lines are a table of two columns (#270):
 * the tags right-aligned in the first, as wide as the widest, so every line's text starts at the
 * same place.
 *
 * With *Show grounding details* on ([SourcesShown.details]), the table has every source's row, as
 * [SourcesShown.chips] has them, the sources that are off too, with the statuses that are
 * diagnostics ("Found the track and the artist (kept)", "Match rejected"), in the developer mark; and the list ends
 * with the matches the sources rejected, with their reasons.
 *
 * The list unfolds a screenful ([Fold.PAGE] lines) at a time ([fold], #269), paged by one group of
 * buttons under it (#666), *Show less* | *Show more* | *Show all*, each disabled where it does not
 * apply ([FoldControls]).
 * The unverified lines and the rejected matches are part of the list, after the evidence lines,
 * under their own headings. A citation tapped opens the panel and unfolds it to the line cited
 * ([LineFocus.cite]), the focus staying where it was. A screen reader reads the chip as a button,
 * *Sources*, collapsed; each paging button by its label, a disabled one as such; and the list says
 * how much of it shows.
 *
 * When the insight shown is the one written earlier ([SourcesShown.cardKept]: the pipeline shows the card paid
 * for in place of an insight the run lacks, `PipelineView.cardShown`, whether Rewrite is offered,
 * a rewrite failed, or neither), the lines are those it cites, found when it was written, beside
 * the sources' states now.
 * While the phone is offline and a source is unreachable ([SourcesShown.offline], #192), it says
 * so: those sources are asked again once it is back online.
 *
 * This is the section as a box (*Layout*'s *Expandable boxes*, #447): its chip, or its panel with
 * [SourcesBody] in it, which a page of its own mounts under *Separate pages*.
 */
@Composable
internal fun SourcesSection(
    shown: SourcesShown,
    focus: LineFocus,
    fold: SourcesFold,
    openSettings: () -> Unit = {},
) {
    val handoff = rememberFocusHandoff()
    val chip = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    // Whether the input focus is in the panel, as last told: plain, read as the panel folds.
    val inside = remember { BooleanArray(1) }
    val folded = fold.fold == Fold.Folded
    val before = remember { BooleanArray(1).also { it[0] = folded } }
    // Folded by other than *Close* (a new track) while the focus was in the panel: back to the chip,
    // or a keyboard's focus would fall to the top of the screen. Read before the panel goes.
    val handBack = folded && !before[0] && inside[0]
    SideEffect {
        before[0] = folded
        if (handBack) handoff.moveTo(chip)
    }
    if (folded) {
        SourcesChip(opensPage = false, modifier = Modifier.handoffTarget(handoff, chip)) {
            // Every entry hidden by the chips: unfolding shows their lines, not an empty list.
            if (entriesOf(shown.track, shown.lines.evidence, focus.hidden, shown.details).isEmpty()) focus.showAll()
            fold.set(Fold.Pages(1))
            handoff.moveTo(close)
        }
    } else {
        Box(Modifier.onFocusChanged { inside[0] = it.hasFocus }) {
            Panel(
                AnnotatedString(stringResource(R.string.home_sources_title)),
                SOURCES_PANEL_TAG,
                onClose = {
                    fold.set(Fold.Folded)
                    handoff.moveTo(chip)
                },
                closeModifier = Modifier.handoffTarget(handoff, close),
            ) {
                SourcesBody(shown, focus, fold, openSettings) { handoff.moveTo(close) }
            }
        }
    }
}

/**
 * What *Sources* shows of a [track]: its [chips] (every source's, the table without those that are
 * off unless with grounding [details]), whether the insight shown is the card kept from before
 * ([cardKept]), and whether the phone is [offline] with a source unreachable. Its lines are [lines]:
 * the track's evidence, with the album's own lines once its insight gathered them (#522,
 * [SourcesLines]), which the chips show and hide as the track's. [retry] of the sources that could
 * not be reached is *Retry* beside the note that says so (#1121), null while it is not offered.
 * [unsupported] are the ids of the lines the key insight cites only from sentences they do not back
 * (#1036, [unsupportedLines]), whose tags show as their chips there do: a dim outline.
 */
@Suppress("LongParameterList") // What the section shows, each its own.
internal class SourcesShown(
    val track: TrackState?,
    val chips: List<SourceChip>,
    val cardKept: Boolean,
    val offline: Boolean = false,
    val details: Boolean = false,
    val lines: SourcesLines = SourcesLines(track?.evidence),
    val retry: (Collection<SourceId>) -> (() -> Unit)? = { null },
    val unsupported: Set<String> = emptySet(),
)

/**
 * The section's content (#447: one component, mounted in place in its panel or on a page of its
 * own): the table of the sources, the note, and the list as far as [fold] unfolds it, with its paging. Drawn as a
 * column of its own, spaced as the section is. A tap on the note's *Retry* (#1121) then runs
 * [afterRetry], which hands the focus on as *Retry* goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesBody(
    shown: SourcesShown,
    focus: LineFocus,
    fold: SourcesFold,
    openSettings: () -> Unit = {},
    afterRetry: () -> Unit = {},
) {
    val track = shown.track
    val details = shown.details
    val hidden = focus.hidden
    val shownLines = shown.lines
    val lines = shownLines.evidence?.lines.orEmpty()
    val unverified = track?.let(::unverifiedLines).orEmpty()
    val entries = entriesOf(track, shownLines.evidence, hidden, details)
    // The lines the chips hide, unverified ones too (#951): with every entry hidden, the count still shows.
    val filtered = lines.count { it.source in hidden } + unverified.count { it.source in hidden }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Of every line, hidden ones too, so the column does not move when a chip hides some.
        val tagWidth = tagColumnWidth(lines.map { it.id } + unverified.map { it.source.tag })
        val chips = shown.chips
        SourceTable(tableRows(chips, shownLines.evidence, details), details, lines, focus, fold, unverified)
        Notes(sourceNotes(chips, shown.offline), openSettings, shown.retry, afterRetry)
        if (shown.offline) Small(stringResource(R.string.home_sources_offline))
        if (lines.isEmpty()) {
            // Still looking, with the turning record before it (#785); nothing found, without, the
            // same node; nothing asked for a track the last clear holds (#1112), without too.
            val gathered = track?.gathered == true
            val cleared = LocalTrackCleared.current && track == null
            Busy(
                when {
                    cleared -> stringResource(R.string.home_cleared)
                    gathered -> stringResource(R.string.home_evidence_empty)
                    else -> stringResource(R.string.home_evidence_looking)
                },
                MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                record = !gathered && !cleared,
            )
        }
        if (entries.isEmpty() && filtered == 0) return@Column
        val total = entries.size
        val count = fold.fold.shown(total)
        FoldStatus(fold, count, total, filtered)
        if (count == 0) return@Column
        if (shown.cardKept && lines.isNotEmpty()) Small(stringResource(R.string.home_evidence_cited))
        Entries(entries.take(count), tagWidth, focus, shownLines.album, shown.unsupported)
        FoldControls(fold, count, total)
    }
}

/**
 * The list's entries for [track]: the lines shown ([evidence], its own with its album's, #522) and
 * its unverified lines (#951), but those of the [hidden] sources, and with grounding [details] the
 * matches rejected.
 */
private fun entriesOf(
    track: TrackState?,
    evidence: EvidenceSet?,
    hidden: Set<SourceId>,
    details: Boolean,
): List<Entry> {
    val lines = evidence?.lines.orEmpty()
    val unverified = track?.let(::unverifiedLines).orEmpty()
    val rejected = if (details) track?.let(::rejectedMatches).orEmpty() else emptyList()
    return lines.filter { it.source !in hidden }.map(Entry::Line) +
        unverified
            .withIndex()
            .filter { it.value.source !in hidden }
            .map { (index, line) -> Entry.Unverified(line, index) } +
        rejected.mapIndexed { index, match -> Entry.Rejected(match, index) }
}

/**
 * An entry of the list: an evidence line, a line not verified, or a match rejected; [key] keeps
 * each row's state (a flash, its way into view) with it as the rows before it come and go.
 */
private sealed interface Entry {
    val key: String

    class Line(
        val line: EvidenceLine,
    ) : Entry {
        override val key: String get() = line.id
    }

    class Unverified(
        val line: EvidenceDraft,
        index: Int,
    ) : Entry {
        override val key: String = unverifiedKey(index)
    }

    class Rejected(
        val match: RejectedMatch,
        index: Int,
    ) : Entry {
        override val key: String = "rejected:$index"
    }
}

/**
 * [entries], the unverified lines and the rejected matches each under their heading. The rejected
 * matches, last, show only in developer mode, and are marked as such together (#536).
 */
@Composable
private fun Entries(
    entries: List<Entry>,
    tagWidth: Dp,
    focus: LineFocus,
    album: Set<String>,
    unsupported: Set<String>,
) {
    val rejected = entries.indexOfFirst { it is Entry.Rejected }.takeIf { it >= 0 } ?: entries.size
    // The rows at [indices], each under its kind's heading when the one before is of another kind.
    val rows: @Composable (IntRange) -> Unit = { indices ->
        for (index in indices) {
            val entry = entries[index]
            val previous = entries.getOrNull(index - 1)
            key(entry.key) {
                when (entry) {
                    is Entry.Line -> {
                        val line = entry.line
                        val flashed = focus.flash?.let { it.flashes && it.id == line.id } == true
                        val row = LineLook(flashed, line.id in album, line.id in unsupported)
                        EvidenceRow(line, tagWidth, row, focus.requesters[line.id])
                    }

                    is Entry.Unverified -> {
                        if (previous !is Entry.Unverified) Small(stringResource(R.string.home_unverified))
                        UnverifiedRow(entry.line, tagWidth, entry.key, focus)
                    }

                    is Entry.Rejected -> {
                        if (previous !is Entry.Rejected) Small(stringResource(R.string.home_rejected_title))
                        val match = entry.match
                        val name = rejectedName(match)
                        val reason = match.reason
                        Small(
                            match.subject?.let { stringResource(R.string.home_rejected_subject, name, it, reason) }
                                ?: stringResource(R.string.home_rejected, name, reason),
                        )
                    }
                }
            }
        }
    }
    rows(0 until rejected)
    if (rejected < entries.size) DeveloperMark(spacing = 8.dp) { rows(rejected until entries.size) }
}

/**
 * One evidence line: its id, its text, and its scope with a link to where it was found; flashed
 * when a citation points at it. A line found in the track's language (#902, `EvidenceLine.language`)
 * shows its text as found, set in that language's locale (a span over the whole text, so a screen
 * reader is given the language too, not only the drawing), and says the language after the host:
 * *release · hi.wikipedia.org · in Hindi*. Its tag is a dim outline when the insight's sentences
 * that cite it are not backed by it (#1036), as their chips are.
 */
@Composable
private fun EvidenceRow(
    line: EvidenceLine,
    tagWidth: Dp,
    look: LineLook,
    requester: BringIntoViewRequester?,
) {
    val flashed = look.flashed
    val album = look.album
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (flashed) colors.tertiaryContainer else Color.Transparent, label = "flash")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(LINE_PREFIX + line.id)
                .let { if (requester != null) it.bringIntoViewRequester(requester) else it }
                .background(background, RoundedCornerShape(8.dp))
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Tag(line.id, tagWidth, look.unsupported)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(inLanguage(line.text, line.language), style = MaterialTheme.typography.bodySmall)
            val language = line.language?.let { stringResource(R.string.home_source_line_in, languageName(it)) }
            Where(if (album) stringResource(R.string.home_scope_album) else scopeText(line.scope), line.url, language)
        }
    }
}

/**
 * The tags' column: as wide as the widest of [tags] as [Tag] draws them. Summed in pixels, as the
 * padding is laid out (each side rounded on its own): at 420 dpi, 4 dp is 10.5 px, so 8 dp rounded
 * once is a pixel short of the padding's 22 (#270).
 */
@Composable
private fun tagColumnWidth(tags: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    return remember(tags, style, density) {
        val widest = tags.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0
        with(density) { (widest + 2 * TAG_PADDING.roundToPx()).toDp() }
    }
}

/**
 * How an evidence row shows: [flashed] when a citation points at it, its scope said as the album's
 * ([album], #522), and its tag a dim outline when it does not back the sentences citing it
 * ([unsupported], #1036).
 */
private class LineLook(
    val flashed: Boolean,
    val album: Boolean,
    val unsupported: Boolean,
)

/**
 * A line's tag, right-aligned in the tags' column, [width] wide: filled, or, when the line does not
 * back the insight's sentences citing it ([unsupported], #1036), with no fill, a thin outline and its
 * text in the dimmed text's colour, as their chips show.
 */
@Composable
private fun Tag(
    text: String,
    width: Dp,
    unsupported: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val look =
        if (unsupported) {
            Modifier.border(1.dp, colors.onSurfaceVariant, shape).testTag(UNSUPPORTED_TAG_PREFIX + text)
        } else {
            Modifier.background(colors.secondaryContainer, shape)
        }
    Box(modifier = Modifier.width(width), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (unsupported) colors.onSurfaceVariant else colors.onSecondaryContainer,
            // Never wrapped, should the column still come out short: the box is fixed, so the last
            // glyph would lose a pixel to the clip at worst.
            softWrap = false,
            maxLines = 1,
            modifier =
                look.padding(horizontal = TAG_PADDING, vertical = 1.dp),
        )
    }
}

/**
 * What a line is about ([scope], in words: "artist", or "album" for the album's own line, #522), and
 * the host it was found on, linked; the link opens in the browser. Then, for a line found in the
 * track's language, [language]: "in Hindi" (#902).
 */
@Composable
private fun Where(
    scope: String,
    url: String?,
    language: String? = null,
) {
    val host = hostOf(url)
    val parts =
        buildList<TextPart> {
            add(TextPart.Words(scope))
            if (url != null && host != null) addAll(listOf(TextPart.Words(" · "), TextPart.Link(host, url)))
            language?.let { add(TextPart.Words(" · $it")) }
        }
    LinkedText(parts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A line of a match that could not be verified: shown, never cited. */
@Composable
private fun UnverifiedRow(
    line: EvidenceDraft,
    tagWidth: Dp,
    id: String,
    focus: LineFocus,
) {
    // Its way into view, for a chip that shows it again to scroll to (#951), known to [focus] while
    // the row is composed.
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(focus, id, requester) {
        val forget = focus.register(id, requester)
        onDispose { forget() }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.bringIntoViewRequester(requester).padding(4.dp),
    ) {
        Tag(line.source.tag, tagWidth)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodySmall)
            Where(scopeText(line.scope), line.url)
        }
    }
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val TAG_PADDING = 4.dp

/** The test tag of an evidence line, before its id. */
const val LINE_PREFIX: String = "line:"

/** The test tag of a line's tag drawn as a dim outline (#1036), before the line's id. */
const val UNSUPPORTED_TAG_PREFIX: String = "unsupported:"

/** The test tags of *Sources*' panel on Home, its heading and its *Close* (#623), as [Panel] makes them. */
const val SOURCES_PANEL_TAG: String = "sources-panel"
const val SOURCES_PANEL_HEADING_TAG: String = "sources-panel:heading"
const val SOURCES_PANEL_CLOSE_TAG: String = "sources-panel:close"
