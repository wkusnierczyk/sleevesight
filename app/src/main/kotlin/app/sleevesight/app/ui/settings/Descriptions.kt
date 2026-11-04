package app.sleevesight.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.settings.DescriptionsOnReturn
import app.sleevesight.core.settings.Settings

// Settings' descriptions folded to one line, a tap unfolding them (#847).

/**
 * Which descriptions are folded (#847) in this visit to the settings, a rotation keeping them; a
 * description is known by its section and a key of its own ([Description]). One not tapped since its
 * section opened shows as *Descriptions on return* had it when the section was last opened
 * ([modeOf]): *Folded* folded, *Unfolded* open, *As left* as stored (`Settings.unfoldedDescriptions`),
 * so as last left, across restarts too. A change of the choice waits for the next return, so nothing
 * on the screen moves under the tap that changed it. Coming back, opening the settings again or a
 * section again ([reopen]), or back from a page the settings opened ([leave], [comeBack], #874),
 * *Folded* and *Unfolded* forget the taps in it. While a search finds a description, it shows unfolded whatever
 * was set for it, and a tap on it folds it only for that search ([endSearch] forgets those), as
 * [SectionFolds] does with the sections.
 */
@Stable
internal class DescriptionFolds(
    entered: DescriptionsOnReturn,
    folded: Map<String, Boolean> = emptyMap(),
    inSearch: Map<String, Boolean> = emptyMap(),
    opened: Map<SettingsSection, DescriptionsOnReturn> = emptyMap(),
) {
    // *Descriptions on return* as the settings were opened, or come back to from a page: for the
    // sections not opened since.
    private var entered by mutableStateOf(entered)

    // *Descriptions on return* as each section was last opened in this visit ([reopen]).
    private val opened = mutableStateMapOf<SettingsSection, DescriptionsOnReturn>().apply { putAll(opened) }

    // Each description tapped since its section opened, by section and key: whether it is folded.
    private val folded = mutableStateMapOf<String, Boolean>().apply { putAll(folded) }

    // Whether the settings were left for a page that comes back to them ([leave]) and are not yet
    // on screen again ([comeBack]); not read in composition, only saved with the taps.
    private var left = false

    // Whether *Descriptions on return* was applied for the way back already, on restoring them.
    private var applied = false

    // Each description a search found and a tap folded or unfolded again during that search.
    private val inSearch = mutableStateMapOf<String, Boolean>().apply { putAll(inSearch) }

    /** *Descriptions on return* as [section] was last opened, or the settings, for one not opened since. */
    fun modeOf(section: SettingsSection?): DescriptionsOnReturn = section?.let { opened[it] } ?: entered

    /** Whether the description [id] was tapped folded or open since its section opened; null if not. */
    fun tapped(
        id: String,
        found: Boolean,
    ): Boolean? = if (found) inSearch[id] ?: false else folded[id]

    /** The description [id] tapped, now [shut] or open. */
    fun set(
        id: String,
        found: Boolean,
        shut: Boolean,
    ) {
        if (found) inSearch[id] = shut else folded[id] = shut
    }

    /**
     * [section] opened, [mode] as *Descriptions on return* is, which its descriptions follow until it
     * is opened again: *Folded* and *Unfolded* forget the taps in it, so its descriptions show as the
     * mode has them; *As left* keeps them.
     */
    fun reopen(
        section: SettingsSection,
        mode: DescriptionsOnReturn,
    ) {
        opened[section] = mode
        if (mode == DescriptionsOnReturn.AS_LEFT) return
        folded.keys.filter { it.startsWith(idPrefix(section)) }.forEach { folded.remove(it) }
    }

    /**
     * The settings left for a page that comes back to them (#874): *Credits*, *Diagnostics*, *What
     * leaves the device*. Nothing changes while they leave; on the way back, restored ([saver]) or
     * on screen again with nothing restored ([comeBack]), *Descriptions on return* applies as on
     * opening the settings. A rotation, which never calls this, keeps the taps.
     */
    fun leave() {
        left = true
        applied = false
    }

    /**
     * The settings on screen again (resumed), [mode] as *Descriptions on return* is (#874). Back from
     * a page they were left for ([leave]), it applies as on opening them, unless restoring them on
     * the way back applied it already ([saver]): so also where they were never taken down, as when
     * Android's back closes the page while it is still opening, and where they were drawn under a
     * back gesture that was then let go. Otherwise nothing changes.
     */
    fun comeBack(mode: DescriptionsOnReturn) {
        if (!left) return
        if (!applied) returnAs(mode)
        left = false
        applied = false
    }

    // Back from a page: as on opening the settings, the search's own taps kept with it.
    private fun returnAs(mode: DescriptionsOnReturn) {
        entered = mode
        opened.clear()
        if (mode != DescriptionsOnReturn.AS_LEFT) folded.clear()
    }

    /** The search ended: the descriptions it found fold again as they were before it. */
    fun endSearch() {
        inSearch.clear()
    }

    companion object {
        /**
         * Saved as the ids tapped, each after `1` (folded) or `0` (open); those tapped in the search
         * under way after [FOUND] and then `1` or `0`; the mode on entering after [ENTERED], and each
         * section's after [OPENED], its name and `=`; and [LEFT] from [leave] until [comeBack].
         * Restored with [LEFT] in it, back from a page (#874), the settings take [mode], as on
         * opening them, and *Folded* and *Unfolded* forget the taps; *As left* keeps them. [LEFT]
         * stays until they are on screen again, so drawn again before that, under a back gesture let
         * go, they apply it again on the way back.
         */
        fun saver(mode: DescriptionsOnReturn): Saver<DescriptionFolds, ArrayList<String>> =
            Saver(
                save = { folds ->
                    val tapped = folds.folded.map { (id, f) -> tap(f) + id }
                    val found = folds.inSearch.map { (id, f) -> FOUND + tap(f) + id }
                    val modes = folds.opened.map { (section, m) -> "$OPENED${section.name}=${m.name}" }
                    val left = if (folds.left) listOf(LEFT) else emptyList()
                    ArrayList(tapped + found + modes + (ENTERED + folds.entered.name) + left)
                },
                restore = { saved -> restored(saved, mode) },
            )

        private fun tap(folded: Boolean) = if (folded) "1" else "0"

        // Each id after its `1` (folded) or `0` (open): whether it is folded.
        private fun taps(saved: List<String>): Map<String, Boolean> =
            saved.associate { it.drop(1) to it.startsWith("1") }

        private fun restored(
            saved: List<String>,
            mode: DescriptionsOnReturn,
        ): DescriptionFolds {
            val tapped = taps(saved.filter { it.startsWith("1") || it.startsWith("0") })
            val found = taps(saved.filter { it.startsWith(FOUND) }.map { it.drop(FOUND.length) })
            val opened =
                saved.filter { it.startsWith(OPENED) }.mapNotNull { s ->
                    val (section, m) = s.drop(OPENED.length).split("=", limit = 2).let { it[0] to it.getOrNull(1) }
                    val known = SettingsSection.entries.firstOrNull { it.name == section }
                    val chosen = DescriptionsOnReturn.entries.firstOrNull { it.name == m }
                    if (known != null && chosen != null) known to chosen else null
                }
            val entered =
                saved.firstOrNull { it.startsWith(ENTERED) }?.drop(ENTERED.length)?.let { m ->
                    DescriptionsOnReturn.entries.firstOrNull { it.name == m }
                } ?: mode
            val folds = DescriptionFolds(entered, tapped, found, opened.toMap())
            if (LEFT in saved) {
                folds.left = true
                folds.returnAs(mode)
                folds.applied = true
            }
            return folds
        }

        // What starts each saved entry other than a tap's `1` or `0`, each a character of its own.
        private const val FOUND = "f"
        private const val ENTERED = "="
        private const val OPENED = "@"
        private const val LEFT = "<"

        /** The start of the id of every description in [section]. */
        fun idPrefix(section: SettingsSection?): String = "${section?.name.orEmpty()}/"
    }
}

/**
 * [DescriptionFolds] for this visit to the settings, as [mode], *Descriptions on return*, has them
 * on opening them, a rotation keeping them; back from a page the settings opened
 * ([DescriptionFolds.leave]), restored as [mode] has them again (#874), or, not restored, once on
 * screen again ([DescriptionFolds.comeBack]).
 */
@Composable
internal fun rememberDescriptionFolds(mode: DescriptionsOnReturn): DescriptionFolds =
    rememberSaveable(saver = DescriptionFolds.saver(mode)) { DescriptionFolds(mode) }

/**
 * The descriptions' [folds] as the settings show them, while *Foldable descriptions* is on:
 * [unfolded], the ids of those left unfolded, as stored; [save] stores a description's id as
 * unfolded or not.
 */
internal class FoldsShown(
    val folds: DescriptionFolds,
    val unfolded: Set<String>,
    val save: (id: String, unfolded: Boolean) -> Unit,
) {
    /**
     * Whether the description [key] in [section] shows folded, as *Descriptions on return* was when
     * [section] was last opened ([DescriptionFolds.modeOf]); [found] by the search, open until tapped.
     */
    fun isFolded(
        section: SettingsSection?,
        key: String,
        found: Boolean,
    ): Boolean {
        val id = descriptionId(section, key)
        return folds.tapped(id, found) ?: when (folds.modeOf(section)) {
            DescriptionsOnReturn.FOLDED -> true
            DescriptionsOnReturn.UNFOLDED -> false
            DescriptionsOnReturn.AS_LEFT -> id !in unfolded
        }
    }

    /**
     * A tap on the description [key] in [section]: folds it if open, else opens it, and stores which.
     * One the search under way found ([found]) folds and opens for that search alone: nothing of it
     * is stored, and clearing the search shows it as the person left it.
     */
    fun toggle(
        section: SettingsSection?,
        key: String,
        found: Boolean,
    ) {
        val id = descriptionId(section, key)
        val shut = !isFolded(section, key, found)
        folds.set(id, found, shut)
        if (!found) save(id, !shut)
    }
}

/**
 * The id a description is folded and stored by (#847): its section's name and [key], the row's
 * name as shown (a provider's name in it keeps each provider's rows apart), or a section's or note's
 * own string's name. Shown in the app's language, a row's name changes with it: after a change of
 * language its description shows folded again, as on a first visit, which is all that is lost.
 */
internal fun descriptionId(
    section: SettingsSection?,
    key: String,
): String = DescriptionFolds.idPrefix(section) + key

/**
 * The descriptions' folds of the settings shown; null, none folds: outside the settings screen, and
 * with *Foldable descriptions* off.
 */
internal val LocalDescriptionFolds = compositionLocalOf<FoldsShown?> { null }

/** The section whose panel the descriptions drawn are in, for their folds (#847). */
internal val LocalDescriptionSection = compositionLocalOf<SettingsSection?> { null }

/**
 * [stored], the ids of the descriptions left unfolded, oldest change first, with [id] unfolded
 * (moved to the end, as the latest change) or not (taken out); at most [limit] kept, the oldest
 * going first, so ids no longer used (a row gone, a language changed) do not pile up.
 */
internal fun unfoldedIds(
    stored: List<String>,
    id: String,
    unfolded: Boolean,
    limit: Int = Settings.UNFOLDED_DESCRIPTIONS_LIMIT,
): List<String> {
    val kept = stored - id
    return (if (unfolded) kept + id else kept).takeLast(limit)
}

/** The test tag of a description that folds (#847). */
internal const val DESCRIPTION_TAG = "settings_description"

/**
 * A setting's or a section's description, [text], in body small and [color] (#847). Where it does
 * not fit on one line, it folds ([LocalDescriptionFolds]): folded, it shows its paragraphs joined by
 * a space, cut to one line, an ellipsis at its end, and nothing else marks it; a tap unfolds it to
 * its paragraphs (with [link] set in the one that has its text, [sentenceWith]), and another folds
 * it again. Its tap is its own, reaching [reach] below it but not over the row's name: on a
 * [SwitchRow], a tap on it no longer toggles the switch. [reach] is at most the room before a
 * control that follows it whose tap reaches past its bounds only by Compose's widening (a button
 * group, 4 dp below it in a [ChoiceButtonsRow]), which a tap in the description's bounds would take
 * from it. A screen reader reads it as a node of its own, the whole text folded or not,
 * *collapsed* or *expanded*, a tap to *Expand* or *Collapse* it. One that fits on one line, or with
 * descriptions not foldable, shows in full as before, with no tap of its own. [key] tells the
 * description from the others for its fold, within its section ([LocalDescriptionSection],
 * [descriptionId]): its row's name, or a section's or note's own string's name.
 * A search that finds [text] shows it open, the query marked in it ([RowSearch]).
 */
@Suppress("LongParameterList") // What it shows and how; all but the first two have defaults.
@Composable
internal fun Description(
    key: String,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    link: TextPart.Linked? = null,
    reach: Dp = TAP_BELOW,
) {
    val shown = LocalDescriptionFolds.current
    if (shown == null) {
        DescriptionParagraphs(text, modifier, color, link, mark = false)
        return
    }
    val style = MaterialTheme.typography.bodySmall
    val line = remember(text) { oneLine(text) }
    // Until laid out, taken not to fit: one that does shows the same either way.
    var width by remember { mutableIntStateOf(0) }
    val measurer = rememberTextMeasurer()
    val fits =
        width > 0 &&
            remember(line, style, width, measurer) {
                val measured = measurer.measure(line, style, maxLines = 1, constraints = Constraints(maxWidth = width))
                !measured.hasVisualOverflow
            }
    val measured = modifier.fillMaxWidth().onSizeChanged { width = it.width }
    if (fits) {
        DescriptionParagraphs(text, measured, color, link)
        return
    }
    val search = LocalRowSearch.current
    val found = search != null && found(text, search.query)
    val section = LocalDescriptionSection.current
    val shut = shown.isFolded(section, key, found)
    val state =
        stringResource(if (shut) R.string.settings_description_collapsed else R.string.settings_description_expanded)
    val action =
        stringResource(if (shut) R.string.settings_description_expand else R.string.settings_description_collapse)
    // Its tap only where it is drawn: Compose widens a small target's touch area to the minimum
    // (48 dp), which would reach over the row's name and take its taps from the switch. What is in
    // it, a link, keeps the minimum. Below it, where the name is not, it reaches [reach] further
    // (#874), into the row's padding, so a tap a little low still folds it rather than toggling the
    // switch. What comes after it in the row keeps its own taps: a control whose bounds reach there
    // (a note's link) is placed later and takes them first; a button group's buttons, which reach
    // there only by Compose's widening, start beyond [reach].
    val configuration = LocalViewConfiguration.current
    val exact = remember(configuration) { ExactTouch(configuration) }
    CompositionLocalProvider(LocalViewConfiguration provides exact) {
        Box(
            measured
                .reachingBelow(reach)
                .testTag(DESCRIPTION_TAG)
                .clickable(onClickLabel = action) { shown.toggle(section, key, found) }
                .semantics { stateDescription = state }
                .padding(bottom = reach),
        ) {
            CompositionLocalProvider(LocalViewConfiguration provides configuration) {
                if (shut) {
                    Text(
                        searchMarked(line),
                        style = style,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    DescriptionParagraphs(text, color = color, link = link)
                }
            }
        }
    }
}

/** How far below a foldable description its tap reaches (#874) where nothing needs the room: a row's bottom padding. */
internal val TAP_BELOW = 8.dp

/**
 * What follows, [below] taller at its bottom, laid out as if it were not: the rows around it stay
 * where they were, and what is in it, a tap target, reaches [below] over what is under it ([Description]).
 */
private fun Modifier.reachingBelow(below: Dp): Modifier =
    layout { measurable, constraints ->
        val reach = below.roundToPx()
        val placeable = measurable.measure(constraints.offset(vertical = reach))
        layout(placeable.width, (placeable.height - reach).coerceAtLeast(0)) { placeable.place(0, 0) }
    }

/** [base], but a touch reaches a target only within its bounds, however small ([Description]). */
private class ExactTouch(
    base: ViewConfiguration,
) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
}

/**
 * [text], in body small, a paragraph for each part a blank line starts ([paragraphsOf]), the
 * search's query marked in it ([searchMarked]) if [mark]; [link] set in the paragraph that has its
 * text. As [Paragraphs], with the link (#802's two paragraphs).
 */
@Composable
private fun DescriptionParagraphs(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    link: TextPart.Linked? = null,
    mark: Boolean = true,
) {
    val style = MaterialTheme.typography.bodySmall
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PARAGRAPH_GAP)) {
        for (paragraph in paragraphsOf(text)) {
            if (link != null && link.text in paragraph) {
                // The search's marks laid over the link's sentence, the link kept.
                val marks = if (mark) searchMarked(paragraph).spanStyles else emptyList()
                LinkedText(sentenceWith(paragraph, link), style = style, color = color, marks = marks)
            } else {
                Text(if (mark) searchMarked(paragraph) else AnnotatedString(paragraph), style = style, color = color)
            }
        }
    }
}

/** [text] on one line: its paragraphs, and the lines in them, joined by a space. */
internal fun oneLine(text: String): String = paragraphsOf(text).joinToString(" ") { it.replace(LINE_BREAK, " ") }

/** Whether a search for [query] finds [text]: as [matches] does, whatever the case or the apostrophe. */
private fun found(
    text: String,
    query: String,
): Boolean {
    val needle = folded(query.trim())
    return needle.isNotEmpty() && folded(text).contains(needle, ignoreCase = true)
}

private val LINE_BREAK = Regex("""\s*\n\s*""")
