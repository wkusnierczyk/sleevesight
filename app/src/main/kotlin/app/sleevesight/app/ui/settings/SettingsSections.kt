package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

/**
 * The settings' sections (#444), in the order shown, each with its name, the short description
 * always shown under it, and the fuller explanation shown once it is unfolded, where one is
 * needed. [DEVELOPER] shows only in developer mode, and has neither.
 */
internal enum class SettingsSection(
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

    /** The tag of the section's heading, for tests. */
    val tag: String get() = "settings_section_$name"
}

/**
 * Which sections are unfolded. All are folded when the settings open; within one visit each stays
 * as it was left, through a rotation too, but nothing of it is stored.
 */
@Stable
internal class SectionFolds(
    unfolded: Set<SettingsSection> = emptySet(),
) {
    var unfolded by mutableStateOf(unfolded)
        private set

    fun isUnfolded(section: SettingsSection): Boolean = section in unfolded

    fun toggle(section: SettingsSection) {
        unfolded = if (section in unfolded) unfolded - section else unfolded + section
    }

    companion object {
        val Saver: Saver<SectionFolds, ArrayList<String>> =
            Saver(
                save = { folds -> ArrayList(folds.unfolded.map { it.name }) },
                restore = { names ->
                    SectionFolds(names.mapNotNull { n -> SettingsSection.entries.firstOrNull { it.name == n } }.toSet())
                },
            )
    }
}

/** [SectionFolds] for this visit to the settings, all folded at first. */
@Composable
internal fun rememberSectionFolds(): SectionFolds = rememberSaveable(saver = SectionFolds.Saver) { SectionFolds() }

/**
 * [section]: its heading, which folds and unfolds it ([SectionHeading]), and, unfolded, its fuller
 * explanation and [content].
 */
@Composable
internal fun FoldedSection(
    section: SettingsSection,
    folds: SectionFolds,
    content: @Composable () -> Unit,
) {
    val unfolded = folds.isUnfolded(section)
    SectionHeading(section, unfolded) { folds.toggle(section) }
    if (unfolded) {
        section.details?.let { Hint(it) }
        content()
    }
}

/**
 * A section's heading: a divider above, then its name with the disclosure triangle (right while
 * folded, down while unfolded, as the home screen's folds have it), and its short description
 * under it; the whole of it toggles the fold. A screen reader reads it as a heading with its state,
 * "collapsed" or "expanded", and not the triangle. The one place the headings' style is set:
 * bold, in the text colour, not the accent (#442).
 */
@Composable
internal fun SectionHeading(
    section: SettingsSection,
    unfolded: Boolean,
    toggle: () -> Unit,
) {
    val state =
        stringResource(if (unfolded) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    Column(
        modifier =
            Modifier
                .testTag(section.tag)
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = toggle)
                .semantics {
                    heading()
                    stateDescription = state
                }.padding(vertical = 8.dp),
    ) {
        // Bold, in the text colour, not the accent (#442).
        val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        Row {
            Text(stringResource(section.title), style = style, color = MaterialTheme.colorScheme.onSurface)
            Text(
                " " + stringResource(if (unfolded) R.string.home_fold_unfolded else R.string.home_fold_folded),
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        section.summary?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
