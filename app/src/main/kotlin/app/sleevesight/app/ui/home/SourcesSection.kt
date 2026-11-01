package app.sleevesight.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import app.sleevesight.core.evidence.Scope
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
            if (entriesOf(shown.track, focus.hidden, shown.details).isEmpty()) focus.showAll()
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
                SourcesBody(shown, focus, fold, openSettings)
            }
        }
    }
}

/**
 * What *Sources* shows of a [track]: its [chips] (every source's, the table without those that are
 * off unless with grounding [details]), whether the insight shown is the card kept from before
 * ([cardKept]), and whether the phone is [offline] with a source unreachable.
 */
internal class SourcesShown(
    val track: TrackState?,
    val chips: List<SourceChip>,
    val cardKept: Boolean,
    val offline: Boolean = false,
    val details: Boolean = false,
)

/**
 * The section's content (#447: one component, mounted in place in its panel or on a page of its
 * own): the table of the sources, the note, and the list as far as [fold] unfolds it, with its paging. Drawn as a
 * column of its own, spaced as the section is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesBody(
    shown: SourcesShown,
    focus: LineFocus,
    fold: SourcesFold,
    openSettings: () -> Unit = {},
) {
    val track = shown.track
    val details = shown.details
    val hidden = focus.hidden
    val lines = track?.evidence?.lines.orEmpty()
    val unverified = track?.let(::unverifiedLines).orEmpty()
    val entries = entriesOf(track, hidden, details)
    // The lines the chips hide: with every entry hidden, the count still shows.
    val filtered = lines.count { it.source in hidden }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Of every line, hidden ones too, so the column does not move when a chip hides some.
        val tagWidth = tagColumnWidth(lines.map { it.id } + unverified.map { it.source.tag })
        val chips = shown.chips
        SourceTable(tableRows(chips, track?.evidence, details), details, lines, focus, fold)
        Notes(sourceNotes(chips, shown.offline), openSettings)
        if (shown.offline) Small(stringResource(R.string.home_sources_offline))
        if (lines.isEmpty()) {
            // Still looking, with the turning record before it (#785); nothing found, without, the
            // same node.
            val gathered = track?.gathered == true
            Busy(
                stringResource(if (gathered) R.string.home_evidence_empty else R.string.home_evidence_looking),
                MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                record = !gathered,
            )
        }
        if (entries.isEmpty() && filtered == 0) return@Column
        val total = entries.size
        val count = fold.fold.shown(total)
        FoldStatus(fold, count, total, filtered)
        if (count == 0) return@Column
        if (shown.cardKept && lines.isNotEmpty()) Small(stringResource(R.string.home_evidence_cited))
        Entries(entries.take(count), tagWidth, focus)
        FoldControls(fold, count, total)
    }
}

/**
 * The list's entries for [track]: its evidence lines but those of the [hidden] sources, its
 * unverified lines, and with grounding [details] the matches rejected.
 */
private fun entriesOf(
    track: TrackState?,
    hidden: Set<SourceId>,
    details: Boolean,
): List<Entry> {
    val lines = track?.evidence?.lines.orEmpty()
    val unverified = track?.let(::unverifiedLines).orEmpty()
    val rejected = if (details) track?.let(::rejectedMatches).orEmpty() else emptyList()
    return lines.filter { it.source !in hidden }.map(Entry::Line) +
        unverified.mapIndexed { index, line -> Entry.Unverified(line, index) } +
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
        override val key: String = "unverified:$index"
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
                        EvidenceRow(line, tagWidth, flashed, focus.requesters[line.id])
                    }

                    is Entry.Unverified -> {
                        if (previous !is Entry.Unverified) Small(stringResource(R.string.home_unverified))
                        UnverifiedRow(entry.line, tagWidth)
                    }

                    is Entry.Rejected -> {
                        if (previous !is Entry.Rejected) Small(stringResource(R.string.home_rejected_title))
                        val match = entry.match
                        val name = sourceName(match.source)
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
 * when a citation points at it.
 */
@Composable
private fun EvidenceRow(
    line: EvidenceLine,
    tagWidth: Dp,
    flashed: Boolean,
    requester: BringIntoViewRequester?,
) {
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
        Tag(line.id, tagWidth)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodySmall)
            Where(line.scope, line.url)
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

/** A line's tag, right-aligned in the tags' column, [width] wide. */
@Composable
private fun Tag(
    text: String,
    width: Dp,
) {
    Box(modifier = Modifier.width(width), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            // Never wrapped, should the column still come out short: the box is fixed, so the last
            // glyph would lose a pixel to the clip at worst.
            softWrap = false,
            maxLines = 1,
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                    .padding(horizontal = TAG_PADDING, vertical = 1.dp),
        )
    }
}

/** A line's scope, and the host it was found on, linked; the link opens in the browser. */
@Composable
private fun Where(
    scope: Scope,
    url: String?,
) {
    val host = hostOf(url)
    val parts =
        listOf(TextPart.Words(scopeText(scope))) +
            if (url != null && host != null) listOf(TextPart.Words(" · "), TextPart.Link(host, url)) else emptyList()
    LinkedText(parts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A line of a match that could not be verified: shown, never cited. */
@Composable
private fun UnverifiedRow(
    line: EvidenceDraft,
    tagWidth: Dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
        Tag(line.source.tag, tagWidth)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodySmall)
            Where(line.scope, line.url)
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

/** The test tags of *Sources*' panel on Home, its heading and its *Close* (#623), as [Panel] makes them. */
const val SOURCES_PANEL_TAG: String = "sources-panel"
const val SOURCES_PANEL_HEADING_TAG: String = "sources-panel:heading"
const val SOURCES_PANEL_CLOSE_TAG: String = "sources-panel:close"
