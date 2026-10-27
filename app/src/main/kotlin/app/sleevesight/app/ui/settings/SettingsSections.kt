package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.CHIP_PADDING
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.FocusHandoff
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff

/**
 * The settings' sections (#444), in the order shown, each with its name, the short description
 * shown beside its chip, and the fuller explanation shown at the top of its panel, where one is
 * needed. [DEVELOPER] shows only in developer mode, and has neither.
 */
enum class SettingsSection(
    @StringRes val title: Int,
    @StringRes val summary: Int?,
    @StringRes val details: Int?,
) {
    PLAYERS(R.string.settings_section_players, R.string.settings_players_short, R.string.settings_players_long),
    SOURCES(R.string.settings_section_sources, R.string.settings_sources_short, R.string.settings_sources_long),
    AI_SERVICES(R.string.settings_section_ai, R.string.settings_ai_short, R.string.settings_ai_long),
    SECRETS(R.string.settings_section_keys, R.string.settings_keys_short, R.string.settings_keys_long),
    GENRES(R.string.settings_section_labels, R.string.settings_genres_short, R.string.settings_genres_long),
    INSIGHTS(R.string.settings_section_insights, R.string.settings_insights_short, R.string.settings_insights_long),
    HISTORY(R.string.settings_section_history, R.string.settings_history_short, R.string.settings_history_long),
    APPEARANCE(
        R.string.settings_section_appearance,
        R.string.settings_appearance_short,
        R.string.settings_appearance_long,
    ),
    ABOUT(R.string.settings_section_about, R.string.settings_about_short, R.string.settings_about_long),
    DEVELOPER(R.string.settings_section_developer, null, null),
    ;

    /** The tag of the section's chip, shown while it is closed, for tests. */
    val tag: String get() = "settings_section_$name"

    /** The tag of the section's panel, shown while it is open, for tests; its *Close* is `<panelTag>:close`. */
    val panelTag: String get() = "settings_panel_$name"
}

/**
 * Which sections are open ([unfolded]), and the search under way ([query], #649). All are closed
 * when the settings open; within one visit each stays as it was left, through a rotation too, but
 * nothing of it is stored. While searching, the sections the search finds are open but those closed
 * since it began ([closedInSearch]), and [unfolded] waits, untouched, for the search to be cleared.
 */
@Stable
internal class SectionFolds(
    unfolded: Set<SettingsSection> = emptySet(),
    query: String = "",
    closedInSearch: Set<SettingsSection> = emptySet(),
) {
    var unfolded by mutableStateOf(unfolded)
        private set

    /** What the search box holds; blank while not searching. */
    var query by mutableStateOf(query)
        private set

    /** The sections closed with *Close* during this search. */
    var closedInSearch by mutableStateOf(closedInSearch)
        private set

    /** Whether a search is under way. */
    val searching: Boolean get() = query.isNotBlank()

    /** Whether [section] shows as its panel: while searching, unless closed since the search began. */
    fun isUnfolded(section: SettingsSection): Boolean =
        if (searching) section !in closedInSearch else section in unfolded

    /** Opens [section], if closed: its chip's tap. */
    fun open(section: SettingsSection) {
        if (searching) closedInSearch = closedInSearch - section else unfolded = unfolded + section
    }

    /** Closes [section], if open: its panel's *Close*. */
    fun close(section: SettingsSection) {
        if (searching) closedInSearch = closedInSearch + section else unfolded = unfolded - section
    }

    /**
     * Opens [section] for a link to a setting in it ([SettingsNavigator.go]): a search under way
     * ends first, so the section and the row linked to show whatever the search found.
     */
    fun unfold(section: SettingsSection) {
        search("")
        unfolded = unfolded + section
    }

    /** Searches for [text]; cleared, the sections are open again as before the search. */
    fun search(text: String) {
        query = text
        if (text.isBlank()) closedInSearch = emptySet()
    }

    companion object {
        /** Saved as the query, the sections closed in the search, and then the sections open. */
        val Saver: Saver<SectionFolds, ArrayList<String>> =
            Saver(
                save = { folds ->
                    ArrayList(
                        listOf(folds.query, folds.closedInSearch.joinToString(",") { it.name }) +
                            folds.unfolded.map { it.name },
                    )
                },
                restore = { saved ->
                    SectionFolds(
                        unfolded = sections(saved.drop(2)),
                        query = saved[0],
                        closedInSearch = sections(saved[1].split(",")),
                    )
                },
            )

        private fun sections(names: List<String>): Set<SettingsSection> =
            names.mapNotNull { n -> SettingsSection.entries.firstOrNull { it.name == n } }.toSet()
    }
}

/** [SectionFolds] for this visit to the settings, all closed at first. */
@Composable
internal fun rememberSectionFolds(): SectionFolds = rememberSaveable(saver = SectionFolds.Saver) { SectionFolds() }

/**
 * [section] (#649), under a divider: closed, its chip ([SectionChip]), [chipWidth] wide, with its
 * short description beside it; a tap on the chip opens the section's panel in their place
 * ([Panel], as *Sources*' on Home): the section's name as a heading, *Close* at its top right, then
 * the fuller explanation and [content]; *Close* turns it back into the chip. The focus moves to
 * *Close* on opening and back to the chip on closing (a keyboard's, and a screen reader's;
 * [FocusHandoff]). The panel's heading is where a link to the section itself lands
 * ([SettingsTarget] without a row, [LandingSpot]), its *Close* taking the focus, as on opening.
 *
 * While a search finds the section ([found], [SectionFolds.searching]), it is open unless closed
 * since the search began, its rows those [found] names, or all of them when the section's own name
 * or a description has the query; the query is marked in its name and in its rows' ([RowSearch]).
 */
@Composable
internal fun FoldedSection(
    section: SettingsSection,
    folds: SectionFolds,
    chipWidth: Dp = sectionChipWidth(),
    found: SectionMatch? = null,
    content: @Composable () -> Unit,
) {
    val mark = searchMarkColour()
    val search = found?.takeIf { folds.searching }?.let { RowSearch(folds.query, it.rows, mark) }
    CompositionLocalProvider(LocalRowSearch provides search) { SectionBox(section, folds, chipWidth, content) }
}

// [FoldedSection]'s chip or panel, with the search, if any, as [LocalRowSearch] has it.
@Composable
private fun SectionBox(
    section: SettingsSection,
    folds: SectionFolds,
    chipWidth: Dp,
    content: @Composable () -> Unit,
) {
    val handoff = rememberFocusHandoff()
    val chip = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    if (!folds.isUnfolded(section)) {
        SectionChip(section, chipWidth, Modifier.handoffTarget(handoff, chip)) {
            folds.open(section)
            handoff.moveTo(close)
        }
    } else {
        Panel(
            searchMarked(stringResource(section.title)),
            section.panelTag,
            onClose = {
                folds.close(section)
                handoff.moveTo(chip)
            },
            modifier = Modifier.padding(top = 8.dp),
            closeModifier = Modifier.handoffTarget(handoff, close),
            spacing = 4.dp,
            headingFrame = { heading -> LandingSpot(SettingsTarget(section)) { heading() } },
        ) {
            section.details?.let { Hint(it) }
            content()
        }
    }
}

/**
 * [section]'s chip, drawn as Home's chips are ([ChipFrame]), [width] wide, its name centred in it
 * on one line; beside it, centred on it, the section's short description, up to two lines, clipped
 * with an ellipsis past them (*Developer* has none, and shows its chip alone). A screen reader reads
 * the chip as a heading and a button, by the section's name, collapsed. [modifier] goes on the
 * chip's outside: a focus requester.
 */
@Composable
private fun SectionChip(
    section: SettingsSection,
    width: Dp,
    modifier: Modifier,
    open: () -> Unit,
) {
    val collapsed = stringResource(R.string.settings_section_collapsed)
    val (fill, colour) = chipColours(marked = false)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipFrame(
            fill,
            modifier = modifier.testTag(section.tag).width(width),
            action =
                Modifier
                    .clickable(role = Role.Button, onClick = open)
                    .semantics {
                        heading()
                        stateDescription = collapsed
                    },
        ) {
            Text(
                searchMarked(stringResource(section.title)),
                style = MaterialTheme.typography.labelLarge,
                color = colour,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        section.summary?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The width of every section's chip (#649): the widest of the sections' names, *Developer*'s too,
 * as the chip sets them (in the app's language, font and font size, so a translation, the system
 * font or a large font scale fits), and the chip's padding at each side. Summed in pixels, as the
 * padding is laid out.
 */
@Composable
internal fun sectionChipWidth(): Dp {
    val names = SettingsSection.entries.map { stringResource(it.title) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(names, style, density, measurer) {
        val widest = names.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        with(density) { (widest + 2 * CHIP_PADDING.roundToPx()).toDp() }
    }
}
