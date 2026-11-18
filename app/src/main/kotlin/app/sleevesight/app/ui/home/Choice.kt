package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.FocusHandoff
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.pipeline.TrackState

// The genre chosen in *Genres*' panel, and the genres past the rows' cap shown on a tap (#1281).

/**
 * The genre a tap on a chip chose in *Genres*' panel ([chosen], null when the tap folded it), and
 * whether a chip was tapped on the track ([tapped]): until one is, the panel shows the genre it
 * opens on (#875); and whether *Show more* shows the genres past the rows' cap ([more], #1281).
 */
internal class Choice(
    val chosen: MutableState<String?>,
    val tapped: MutableState<Boolean>,
    val more: MutableState<Boolean> = mutableStateOf(false),
)

/**
 * The genre open in *Genres*' panel for [genres], the chips in [order]: until a chip is tapped, the
 * one the panel opens on ([TrackGenres.opening]: never one past the cap); then the one [Choice.chosen],
 * while a row has it (yet, or any more), and, for one of the [extras] past the rows' cap (#1281), while
 * they show ([Choice.more]).
 */
internal fun Choice.open(
    genres: TrackGenres,
    order: Comparator<String>,
    extras: List<PlacedLabel>,
): String? {
    if (!tapped.value) {
        return genres
            .opening(order)
            ?.label
            ?.genre
            ?.identity
    }
    val extra = extras.any { it.label.genre.identity == chosen.value }
    val has = { genre: String -> genres.openable.any { it.genre.identity == genre } }
    return chosen.value?.takeIf { has(it) && (more.value || !extra) }
}

// Whether [track]'s genres are in, every source asked, with no genre past the rows' cap (#1281):
// what folds the extras for good, as `GenresContent` drops a chosen genre no row has.
internal fun settled(
    track: TrackState,
    extras: List<PlacedLabel>,
) = track.gathered && track.genresAssembled && extras.isEmpty()

/**
 * Shows or folds the genres past the rows' cap ([Choice.more], #1281); folded with them, one of the
 * [extras] that is [open] folds too.
 */
internal fun Choice.toggleMore(
    open: String?,
    extras: List<PlacedLabel>,
) {
    if (more.value && extras.any { it.label.genre.identity == open }) chosen.value = null
    more.value = !more.value
}

/**
 * Under *Genres*' chips (#1281), with [extras], genres past the rows' cap: *Show more*, a pill
 * ([PillButton]) that shows them, read with their count and as collapsed ("Show more, 9 genres with
 * weaker evidence, collapsed", its action "expand"); once [shown], a quiet line, "Weaker evidence",
 * a heading to a screen reader, then [content] (their chips, or their panels on the genres' page),
 * then *Show less*, read as expanded, its action "collapse", which folds them. [toggle] runs on a tap
 * of either, and [handoff] then hands the focus to the other pill, which takes the tapped one's place
 * as the line's control. Nothing with no extras.
 */
@Composable
internal fun MoreGenres(
    extras: List<PlacedLabel>,
    shown: Boolean,
    handoff: FocusHandoff,
    content: @Composable () -> Unit,
    toggle: () -> Unit,
) {
    val showMore = remember { FocusRequester() }
    val showLess = remember { FocusRequester() }
    if (extras.isEmpty()) return
    if (!shown) {
        val count = pluralStringResource(R.plurals.home_genres_more_count, extras.size, extras.size)
        val label = stringResource(R.string.home_genres_show_more)
        val collapsed = stringResource(R.string.home_fold_collapsed)
        PillButton(
            {
                toggle()
                handoff.moveTo(showLess)
            },
            Modifier
                .handoffTarget(handoff, showMore)
                .testTag(GENRES_MORE_TAG)
                .semantics { stateDescription = collapsed },
            named = stringResource(R.string.home_genres_more_named, label, count),
            action = stringResource(R.string.home_genres_expand),
        ) { Text(label) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.home_genres_weaker),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(GENRES_WEAKER_TAG).semantics { heading() },
        )
        content()
    }
    val expanded = stringResource(R.string.home_fold_expanded)
    PillButton(
        {
            toggle()
            handoff.moveTo(showMore)
        },
        Modifier
            .handoffTarget(handoff, showLess)
            .testTag(GENRES_LESS_TAG)
            .semantics { stateDescription = expanded },
        action = stringResource(R.string.home_genres_collapse),
    ) { Text(stringResource(R.string.home_genres_show_less)) }
}
