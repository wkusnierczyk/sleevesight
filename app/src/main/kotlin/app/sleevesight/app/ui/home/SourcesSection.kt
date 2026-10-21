package app.sleevesight.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState

/**
 * The *Sources* section, quiet, at the bottom (design, *Home screen*): a chip per source with its
 * state, which shows or hides that source's lines (a display filter only: what the model may use
 * is the settings'), the chips of a row centred on one line; the evidence lines, each with its
 * scope and a link to where it was found; the lines of matches that could not be verified, which
 * are shown but never cited; and the matches rejected, with their reasons. The lines are a table
 * of two columns (#270): the tags right-aligned in the first, as wide as the widest, so every
 * line's text starts at the same place.
 *
 * The list is folded ([fold], #269): it starts folded, the heading and the chips showing and
 * *show* under them, and unfolds a screenful ([Fold.PAGE] lines) at a time, *more* and *show all*
 * under it while lines remain, *less* back to the first screenful once past it, and *hide* above
 * it and under it, so folding it is never a scroll away. The unverified lines and the rejected
 * matches are part of the list, after the evidence lines, under their own headings. While the
 * list is folded, a chip unfolds it to its source's first line rather than hiding its lines, and
 * a citation tapped unfolds it to the line cited ([LineFocus.cite]). Each control is a button a
 * screen reader names in words, and the list says how much of it shows.
 *
 * When the insight shown is the one written earlier ([cardKept]: the pipeline shows the card paid
 * for in place of an insight the run lacks, `PipelineView.cardShown`, whether Rewrite is offered,
 * a rewrite failed, or neither), the lines are those it cites, found when it was written, beside
 * the sources' states now.
 * While the phone is offline and a source is unreachable ([offline], #192), it says so: those
 * sources are asked again once it is back online.
 */
@Suppress("LongParameterList") // What the section shows, and the two states it is unfolded by.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesSection(
    track: TrackState?,
    chips: List<SourceChip>,
    focus: LineFocus,
    fold: SourcesFold,
    cardKept: Boolean,
    offline: Boolean = false,
) {
    val hidden = focus.hidden
    val folded = fold.fold == Fold.Folded
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.home_sources_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        val lines = track?.evidence?.lines.orEmpty()
        val unverified = track?.let(::unverifiedLines).orEmpty()
        // Of every line, hidden ones too, so the column does not move when a chip hides some.
        val tagWidth = tagColumnWidth(lines.map { it.id } + unverified.map { it.source.tag })
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            for (chip in chips) {
                val filters = lines.any { it.source in chip.filters }
                val shown = chip.filters.none { it in hidden }
                StatusChip(chip, filtering = filters, shown = shown, folded = folded) {
                    // Lines shown again are unfolded to, wherever they fall in the list.
                    (if (folded) focus.reveal(chip) else focus.toggle(chip))?.let(fold::reach)
                }
            }
        }
        if (offline) Small(stringResource(R.string.home_sources_offline))
        if (lines.isEmpty()) {
            val empty = if (track?.gathered == true) R.string.home_evidence_empty else R.string.home_evidence_looking
            Small(stringResource(empty))
        }
        val entries =
            lines.filter { it.source !in hidden }.map(Entry::Line) +
                unverified.mapIndexed { index, line -> Entry.Unverified(line, index) } +
                track
                    ?.let(::rejectedMatches)
                    .orEmpty()
                    .mapIndexed { index, match -> Entry.Rejected(match, index) }
        // The lines the chips hide: with every entry hidden, the fold and its count still show.
        val filtered = lines.count { it.source in hidden }
        if (entries.isEmpty() && filtered == 0) return@Column
        val total = entries.size
        val shown = fold.fold.shown(total)
        FoldStatus(fold, shown, total, filtered)
        if (shown == 0) return@Column
        if (cardKept && lines.isNotEmpty()) Small(stringResource(R.string.home_evidence_cited))
        Entries(entries.take(shown), tagWidth, focus)
        FoldControls(fold, shown, total)
    }
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

/** [entries], the unverified lines and the rejected matches each under their heading. */
@Composable
private fun Entries(
    entries: List<Entry>,
    tagWidth: Dp,
    focus: LineFocus,
) {
    entries.forEachIndexed { index, entry ->
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
                    Small(
                        match.subject?.let { stringResource(R.string.home_rejected_subject, name, it, match.reason) }
                            ?: stringResource(R.string.home_rejected, name, match.reason),
                    )
                }
            }
        }
    }
}

/**
 * Whether the *Sources* section says the phone is offline (#192): while it is ([view]'s
 * `offline`), for a [track] with a source unreachable, which is asked again once the phone is
 * back online.
 */
internal fun offlineShown(
    view: PipelineView,
    track: TrackState?,
): Boolean =
    view.offline &&
        track?.sources?.values?.any { (it as? SourceState.Done)?.result?.outcome == Outcome.Unreachable } == true

/**
 * A source's chip: its tag and state. One whose source has lines ([filtering]) shows or hides them
 * ([shown]); while the list is [folded], it unfolds the list to them instead, a button then.
 */
@Composable
private fun StatusChip(
    chip: SourceChip,
    filtering: Boolean,
    shown: Boolean,
    folded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_source_chip, chip.source.tag, statusText(chip.status))
    val bad = chip.status == ChipStatus.Failed || (chip.status as? ChipStatus.Done)?.outcome is Outcome.Rejected
    val shape = RoundedCornerShape(8.dp)
    // The chip's own text is what it says; the filter is its action and its state.
    val name = sourceName(chip.source)
    val action = stringResource(if (folded) R.string.home_source_reveal else R.string.home_source_filter, name)
    val state = stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
    val toggle =
        when {
            !filtering -> {
                Modifier
            }

            folded -> {
                Modifier
                    .semantics { stateDescription = state }
                    .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            }

            else -> {
                Modifier
                    .semantics {
                        stateDescription = state
                        toggleableState = ToggleableState(shown)
                    }.clickable(onClickLabel = action, role = Role.Switch, onClick = onToggle)
            }
        }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (bad) {
                colors.error
            } else if (filtering && !shown) {
                colors.outline
            } else {
                colors.onSurfaceVariant
            },
        modifier =
            Modifier
                .testTag(CHIP_PREFIX + chip.source.tag)
                .then(if (filtering) Modifier.minimumInteractiveComponentSize() else Modifier)
                .background(if (filtering && shown) colors.secondaryContainer else Color.Transparent, shape)
                .border(1.dp, colors.outlineVariant, shape)
                .then(toggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
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
 * once is a pixel short of the padding's 22 (review of #271).
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
            // Never wrapped, should the column still come out short: a pixel of alignment at worst.
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
    val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    val text =
        buildAnnotatedString {
            append(scopeText(scope))
            val host = hostOf(url)
            if (url != null && host != null) {
                append(" · ")
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(host) }
            }
        }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The test tag of a source's chip, before its tag (`MB`, `W`). */
const val CHIP_PREFIX: String = "chip:"

/** The test tag of an evidence line, before its id. */
const val LINE_PREFIX: String = "line:"
