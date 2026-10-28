package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

/**
 * The genre labels (#439, #558; `design/design.md`, *Genre labels*), of the track, its artists and
 * its album. On Home, the chips alone, with no title: those of the most specific level
 * ([TrackGenres.folded]). A chip opens the genre's panel ([GenrePanel]): under *Expandable boxes*,
 * under the chips, where a second tap on the same chip, or *Close*, closes it and another chip
 * switches it; under *Separate pages* ([openPage], #447), on the genres' page ([HomePage.GenresAt]),
 * the chip's genre open there. The panel shows what the genre is and *Why is this …?* from [notes]
 * (#523). Chips are plain, told apart by their fill, not by a hue per genre
 * family.
 */
@Composable
internal fun Genres(
    track: TrackState?,
    settings: Settings?,
    openPage: ((HomePage) -> Unit)? = null,
    notes: PanelNotes = PanelNotes(),
) {
    // *Show genres* off (#444): no genres are asked for, and none shown, not even that they are off.
    if (track == null || !track.gathered || settings?.showGenres == false) return
    val genres = track.genres
    when {
        genres.isEmpty && labelsOff(settings) -> QuietLine(stringResource(R.string.home_genres_off))
        genres.isEmpty -> QuietLine(stringResource(R.string.home_genres_none))
        // Each chip opens the page, of one level too (#548): a chip that looks like a button and
        // opens nothing reads as broken.
        openPage != null ->
            Chips(genres.folded, open = null, opensPage = true) { openPage(HomePage.GenresAt(it.label.genre.identity)) }
        else -> GenresBox(track, notes)
    }
}

/** Whether every label source is off in [settings], as read. */
internal fun labelsOff(settings: Settings?): Boolean =
    settings != null && LabelSource.entries.none { settings.givesLabels(it) }

/**
 * Under *Expandable boxes*: the chips, and under them the panel of the genre chosen, if any. The
 * focus moves as *Sources*' does ([FocusHandoff], #629): to the panel's *Close* when a chip opens a
 * genre, and back to the chip tapped when *Close* closes it (a keyboard's, and a screen reader's;
 * nothing moves while merely touched). A panel that goes for another reason (a new track, or labels
 * that no longer have its genre) while the focus is in it hands the focus to that chip too, or, when
 * it is gone with the old track, to the first chip; while a new track's genres are still being
 * gathered there is no chip, and nothing to hand the focus to.
 */
@Composable
private fun GenresBox(
    track: TrackState,
    notes: PanelNotes,
) {
    // Closed on each new track. Kept by the genre's identity, so it outlasts labels that land later;
    // a genre no row has any more shows no panel.
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    val open = chosen?.takeIf { track.genres.has(it) }
    val labels = track.genres.folded
    val handoff = rememberFocusHandoff()
    val close = remember { FocusRequester() }
    // A requester per chip shown, by its tag, and the tag of the chip last tapped: plain, read only
    // by the moves.
    val chips = remember(track.normalized.lookupKey) { mutableMapOf<String, FocusRequester>() }
    val tapped = remember { arrayOfNulls<String>(1) }
    val chipOf = { tag: String -> chips.getOrPut(tag) { FocusRequester() } }
    // The chip tapped, or the first, when the one tapped went with the old track.
    val back = {
        val tags = labels.map { tagOf(GENRE_PREFIX, it) }
        (tapped[0]?.takeIf { it in tags } ?: tags.firstOrNull())?.let(chipOf)
    }
    // Whether the input focus is in the panel, as last told: plain, read as the panel goes.
    val inside = remember { BooleanArray(1) }
    val before = remember { BooleanArray(1).also { it[0] = open != null } }
    // Gone by other than *Close* while the focus was in it: back to the chip, or a keyboard's focus
    // would fall to the top of the screen. Read before the panel goes.
    val handBack = open == null && before[0] && inside[0]
    SideEffect {
        before[0] = open != null
        // No panel, no focus in it: a panel that goes may not be told it lost the focus.
        if (open == null) inside[0] = false
        if (handBack) back()?.let(handoff::moveTo)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val target = { label: PlacedLabel -> Modifier.handoffTarget(handoff, chipOf(tagOf(GENRE_PREFIX, label))) }
        Chips(labels, open, chipModifier = target) { label ->
            val genre = label.label.genre.identity
            tapped[0] = tagOf(GENRE_PREFIX, label)
            chosen = if (genre == open) null else genre
            // Opened, or switched to another genre: to *Close*. The same chip again closes the
            // panel, and the focus stays on the chip.
            if (genre != open) handoff.moveTo(close)
        }
        open?.let {
            Box(Modifier.onFocusChanged { state -> inside[0] = state.hasFocus }) {
                GenrePanel(
                    track,
                    it,
                    onClose = {
                        chosen = null
                        back()?.let(handoff::moveTo)
                    },
                    notes = notes,
                    closeModifier = Modifier.handoffTarget(handoff, close),
                )
            }
        }
    }
}

/**
 * [labels] as chips, the one of the genre [open] marked, each under [chipModifier]'s modifier for it
 * (a focus requester); each chip a button that [tap]s it: with [opensPage], to open the genres'
 * page (a screen reader names the action "open page"), else to show the genre's panel, or, the one
 * [open], to close it; unless it does not [close], when the one [open] is not a button.
 */
@Suppress("LongParameterList") // What the chips show and do, and a focus target for each.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(
    labels: List<PlacedLabel>,
    open: String?,
    opensPage: Boolean = false,
    close: Boolean = true,
    chipModifier: (PlacedLabel) -> Modifier = { Modifier },
    tap: (PlacedLabel) -> Unit,
) {
    val showAction = stringResource(R.string.home_genre_show)
    val closeAction = stringResource(R.string.home_genre_close)
    val pageAction = stringResource(R.string.home_page_open)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in labels) {
            // A chip that opens the page is never selected: it opens the page, not a panel beside it.
            val selected = if (opensPage) null else label.label.genre.identity == open
            // On the genres' page the marked chip has nothing to do: not a button there.
            val action =
                when {
                    opensPage -> pageAction
                    selected == true -> closeAction.takeIf { close }
                    else -> showAction
                }
            GenreChip(label, selected, action, chipModifier(label)) { tap(label) }
        }
    }
}

/**
 * A chip on Home and the genres' page (#439, #558): a light fill in the text's colour; while
 * [selected], the text's colour, with the box's colour for the text. A button whose action a screen
 * reader names [action], and says is selected while its genre's panel shows; with [selected] null
 * (a chip that opens the page), neither; with no [action], not a button, and a tap does nothing.
 * [modifier] goes outside its target (a focus requester).
 */
@Composable
private fun GenreChip(
    placed: PlacedLabel,
    selected: Boolean?,
    action: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val (fill, content) = chipColours(selected == true)
    ChipFrame(
        fill,
        modifier = modifier.testTag(tagOf(GENRE_PREFIX, placed)),
        action =
            Modifier
                .let { chip ->
                    action?.let { chip.clickable(role = Role.Button, onClickLabel = it, onClick = onClick) } ?: chip
                }.let { chip -> selected?.let { chip.semantics { this.selected = it } } ?: chip },
    ) {
        Text(placed.label.genre.label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

// Whether any row has [genre].
private fun TrackGenres.has(genre: String): Boolean = all.any { it.genre.identity == genre }

/**
 * The test tag of a chip on Home or the genres' page, before its level (`track`, `artist`,
 * `album`), a colon, for an artist's the artist and a colon, and its identity:
 * `genre:artist:The Beatles:rock`.
 */
const val GENRE_PREFIX: String = "genre:"

/** The test tag of a label in the panel's table, before its level, artist and identity, as [GENRE_PREFIX]. */
const val GENRE_CELL_PREFIX: String = "genre-cell:"

/** The test tag of [placed] after [prefix]: its level, its artist for an artist's, and its identity. */
internal fun tagOf(
    prefix: String,
    placed: PlacedLabel,
): String {
    val artist =
        placed.row.artist
            ?.let { "$it:" }
            .orEmpty()
    return prefix +
        placed.row.level.name
            .lowercase() + ":" + artist + placed.label.genre.identity
}
