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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
 * The *Sources* section, quiet, at the bottom (design, *Home screen*). Its heading is its fold
 * (#440): `Sources ▸` folded, and nothing else; `Sources ▾` unfolded, and under it a chip for each
 * source that gave lines, by name with the letter its citations use ([chipsWithLines]), which shows
 * or hides that source's lines (a display filter only: what the model may use is the settings'),
 * the chips of a row centred on one line; a note when a source needs the listener's key, could not
 * be reached or failed ([sourceNotes]), its key's linking to the settings ([openSettings]); and the
 * list: the evidence lines, each with its scope and a link to where it was found, and the lines of
 * matches that could not be verified, which are shown but never cited. The lines are a table of two
 * columns (#270): the tags right-aligned in the first, as wide as the widest, so every line's text
 * starts at the same place.
 *
 * With *Show grounding details* on ([details]), the chips are every source's, as [chips] has them,
 * each with its letter and its status ("W the song (kept)"), the model's own knowledge's `M` among
 * them, and the list ends with the matches the sources rejected, with their reasons.
 *
 * The list unfolds a screenful ([Fold.PAGE] lines) at a time ([fold], #269): *show more…* and
 * *show all* under it while lines remain, *… show less* back to the first screenful once past it.
 * The unverified lines and the rejected matches are part of the list, after the evidence lines,
 * under their own headings. A citation tapped unfolds it to the line cited ([LineFocus.cite]). The
 * heading is a button a screen reader names *Sources*, collapsed or expanded, each control one it
 * names in words, and the list says how much of it shows.
 *
 * When the insight shown is the one written earlier ([cardKept]: the pipeline shows the card paid
 * for in place of an insight the run lacks, `PipelineView.cardShown`, whether Rewrite is offered,
 * a rewrite failed, or neither), the lines are those it cites, found when it was written, beside
 * the sources' states now.
 * While the phone is offline and a source is unreachable ([offline], #192), it says so: those
 * sources are asked again once it is back online.
 */
@Suppress("LongParameterList") // What the section shows, the states it is unfolded by, and its link.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesSection(
    track: TrackState?,
    chips: List<SourceChip>,
    focus: LineFocus,
    fold: SourcesFold,
    cardKept: Boolean,
    offline: Boolean = false,
    details: Boolean = false,
    openSettings: () -> Unit = {},
) {
    val hidden = focus.hidden
    val lines = track?.evidence?.lines.orEmpty()
    val unverified = track?.let(::unverifiedLines).orEmpty()
    val entries =
        lines.filter { it.source !in hidden }.map(Entry::Line) +
            unverified.mapIndexed { index, line -> Entry.Unverified(line, index) } +
            (if (details) track?.let(::rejectedMatches).orEmpty() else emptyList())
                .mapIndexed { index, match -> Entry.Rejected(match, index) }
    // The lines the chips hide: with every entry hidden, the count still shows.
    val filtered = lines.count { it.source in hidden }
    val folded = fold.fold == Fold.Folded
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SourcesHeading(folded) {
            if (!folded) {
                fold.set(Fold.Folded)
            } else {
                // Every entry hidden by the chips: unfolding shows their lines, not an empty list.
                if (entries.isEmpty()) focus.showAll()
                fold.set(Fold.Pages(1))
            }
        }
        if (folded) return@Column
        // Of every line, hidden ones too, so the column does not move when a chip hides some.
        val tagWidth = tagColumnWidth(lines.map { it.id } + unverified.map { it.source.tag })
        val shownChips = if (details) chips else chipsWithLines(chips, track?.evidence)
        if (shownChips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                for (chip in shownChips) {
                    val filters = lines.any { it.source in chip.filters }
                    val shown = chip.filters.none { it in hidden }
                    // Lines shown again are unfolded to, wherever they fall in the list.
                    val toggle = { focus.toggle(chip)?.let(fold::reach) }
                    if (details) {
                        StatusChip(chip, filtering = filters, shown = shown) { toggle() }
                    } else {
                        NameChip(chip, shown = shown) { toggle() }
                    }
                }
            }
        }
        val notes = sourceNotes(chips, offline)
        if (notes.isNotEmpty()) Notes(notes, openSettings)
        if (offline) Small(stringResource(R.string.home_sources_offline))
        if (lines.isEmpty()) {
            val empty = if (track?.gathered == true) R.string.home_evidence_empty else R.string.home_evidence_looking
            Small(stringResource(empty))
        }
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
 * The section's heading, and its fold (#440): `Sources` and the disclosure triangle of the genres'
 * fold (#439), right while [folded], down while not; the whole line a button, which a screen
 * reader reads as the heading *Sources*, collapsed or expanded.
 */
@Composable
private fun SourcesHeading(
    folded: Boolean,
    toggle: () -> Unit,
) {
    val name = stringResource(R.string.home_sources_title)
    val state = stringResource(if (folded) R.string.home_fold_collapsed else R.string.home_fold_expanded)
    val symbol = stringResource(if (folded) R.string.home_fold_folded else R.string.home_fold_unfolded)
    Text(
        "$name $symbol",
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier
                .testTag(SOURCES_FOLD_TAG)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClick = toggle)
                .clearAndSetSemantics {
                    heading()
                    contentDescription = name
                    stateDescription = state
                }.wrapContentHeight(Alignment.CenterVertically)
                .padding(end = 4.dp),
    )
}

/**
 * What needs the listener, in one note (#440): each source that needs its key, "Last.fm needs your
 * key, in Settings.", the settings a link ([openSettings]); each one that could not be reached or
 * failed.
 */
@Composable
private fun Notes(
    notes: List<SourceNote>,
    openSettings: () -> Unit,
) {
    val settings = stringResource(R.string.home_source_note_settings)
    val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    val sentences =
        notes.map { note ->
            val name = chipName(note.source)
            when (note) {
                is SourceNote.NeedsKey -> stringResource(R.string.home_source_note_key, name, settings)
                is SourceNote.Unreachable -> stringResource(R.string.home_source_note_unreachable, name)
                is SourceNote.Failed -> stringResource(R.string.home_source_note_failed, name)
            } to (note is SourceNote.NeedsKey)
        }
    val text =
        buildAnnotatedString {
            sentences.forEachIndexed { index, (sentence, linked) ->
                if (index > 0) append(" ")
                val at = sentence.lastIndexOf(settings)
                if (!linked || at < 0) {
                    append(sentence)
                    return@forEachIndexed
                }
                append(sentence.substring(0, at))
                withLink(LinkAnnotation.Clickable(SETTINGS_LINK, linkStyle) { openSettings() }) { append(settings) }
                append(sentence.substring(at + settings.length))
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(SOURCES_NOTE_TAG),
    )
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
 * A source's chip under *Show grounding details*: its letter and its status ("W the song"), as the
 * chips were before #440. One whose source has lines ([filtering]) shows or hides them ([shown]).
 */
@Composable
private fun StatusChip(
    chip: SourceChip,
    filtering: Boolean,
    shown: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_source_chip, chip.source.tag, statusText(chip.status))
    val bad = chip.status == ChipStatus.Failed || (chip.status as? ChipStatus.Done)?.outcome is Outcome.Rejected
    val shape = RoundedCornerShape(8.dp)
    // The chip's own text is what it says; the filter is its action and its state.
    val action = stringResource(R.string.home_source_filter, sourceName(chip.source))
    val state = stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
    val toggle =
        if (filtering) {
            Modifier
                .semantics {
                    stateDescription = state
                    toggleableState = ToggleableState(shown)
                }.clickable(onClickLabel = action, role = Role.Switch, onClick = onToggle)
        } else {
            Modifier
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
 * A source's chip (#440): the letter its citations use, small, and its name ("W Wikipedia"); it
 * shows or hides the source's lines ([shown]). A screen reader reads it as the source's name, and
 * whether its lines show.
 */
@Composable
private fun NameChip(
    chip: SourceChip,
    shown: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val name = chipName(chip.source)
    val action = stringResource(R.string.home_source_filter, name)
    val state = stringResource(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden)
    val color = if (shown) colors.onSurfaceVariant else colors.outline
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .testTag(CHIP_PREFIX + chip.source.tag)
                .minimumInteractiveComponentSize()
                .clip(shape)
                .background(if (shown) colors.secondaryContainer else Color.Transparent, shape)
                .border(1.dp, colors.outlineVariant, shape)
                .clickable(onClickLabel = action, role = Role.Switch, onClick = onToggle)
                .clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = state
                    toggleableState = ToggleableState(shown)
                }.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(chip.source.tag, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = LETTER_ALPHA))
        Text(name, style = MaterialTheme.typography.labelMedium, color = color)
    }
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

// The letter beside a chip's name: there to match a citation to its chip, quieter than the name.
private const val LETTER_ALPHA = 0.75f

// The note's link to the settings.
private const val SETTINGS_LINK = "settings"

/** The test tag of a source's chip, before its tag (`MB`, `W`). */
const val CHIP_PREFIX: String = "chip:"

/** The test tag of the section's heading, its fold. */
const val SOURCES_FOLD_TAG: String = "sources-fold"

/** The test tag of the note of what needs the listener. */
const val SOURCES_NOTE_TAG: String = "sources-note"

/** The test tag of an evidence line, before its id. */
const val LINE_PREFIX: String = "line:"
