package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.pipeline.TracklistState
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.ReleaseDate

// The line under the album on Home (#729): the record label of the release being played and the
// album's year, "Parlophone · 1965", worked out from what the pipeline publishes and the tracklists
// looked up, as plain functions.

/** The record labels of the release being played, as far as Home knows them (#729). */
internal sealed interface AlbumLabels {
    /** MusicBrainz is still asked, for the track or for its release: the labels may yet come. */
    data object Looking : AlbumLabels

    /**
     * The [labels] of the release, in MusicBrainz's order, none when it names none (or MusicBrainz is
     * off, did not find the song, or could not be reached); the release's own [year], when known; and
     * the year its release group first came out, [first], when MusicBrainz gives it.
     */
    data class Known(
        val labels: List<String> = emptyList(),
        val year: String? = null,
        val first: String? = null,
    ) : AlbumLabels
}

/**
 * The record labels of the release of [header]'s album being played (#729), with [musicBrainzOn] as
 * *Sources* has it, from the tracklists looked up, [states]: the release the back of the cover shows
 * ([tracklistKey], #984), the one the song is matched to, or of several albums of the name the one the
 * listener chose, of [choices] (#1080). Only that key's own lookup counts: another song's release of
 * the album, which the back shows meanwhile, may be another edition, so it never ends the wait. Known
 * and empty with MusicBrainz off,
 * the song not found or not reached, no album or no release group named, several albums of the name
 * none of which the recording tells apart, or the release not found or not reached.
 */
internal fun albumLabels(
    track: TrackState?,
    header: NowPlaying,
    musicBrainzOn: Boolean,
    states: Map<TracklistKey, TracklistState>,
    choices: Map<String, AlbumChoice> = emptyMap(),
): AlbumLabels {
    val none = NoDates.of(track)
    val chosen = sameName(track, header, choices)?.chosen
    return when {
        !musicBrainzOn || none == NoDates.OFF -> NO_LABELS
        chosen == null && none == NoDates.LOOKING -> AlbumLabels.Looking
        chosen == null && none != NoDates.NO_DATES -> NO_LABELS
        else -> tracklistKey(track, header, choices)?.let { labelsOf(states[it]) } ?: NO_LABELS
    }
}

// The labels of a release as its tracklist's lookup, [state], has them.
private fun labelsOf(state: TracklistState?): AlbumLabels =
    when (state) {
        null, TracklistState.Looking -> AlbumLabels.Looking
        is TracklistState.Found ->
            AlbumLabels.Known(
                state.tracklist.labels,
                state.tracklist.date?.let(ReleaseDate::yearIn),
                state.tracklist.group
                    ?.firstReleaseDate
                    ?.let(ReleaseDate::yearIn),
            )
        TracklistState.NotFound, TracklistState.Unclear, TracklistState.Failed -> NO_LABELS
    }

private val NO_LABELS = AlbumLabels.Known()

/**
 * What the line under the album says (#729): the [labels] of the release played, the [year] after
 * them, and, when it is a later edition, the year the album first came out in brackets after that,
 * [first].
 */
internal data class ReleaseLine(
    val labels: List<String>,
    val year: String?,
    val first: String? = null,
)

/**
 * The line under [album] (#729), from [labels], the release played, and [year], the album's year by
 * [ReleaseDate.albumYear]'s rules (`headerYears`), the one the album line showed before:
 * - with the release known, its own labels and its own year, and in brackets the year its release
 *   group first came out when that is earlier ("Apple Records · 2019 (1969)"; [year] in its place
 *   when MusicBrainz gives the group no date, under the same rule: never a later year, nor another
 *   album's chosen among several of one name, which the release's own group never is); its labels
 *   alone when it has no date; its labels alone
 *   too when the album's name gives its edition's year ([albumLineYear], #1116: "Material Girl (2024
 *   Remaster)");
 * - with no release known (MusicBrainz off, the release not found or with neither a label nor a date,
 *   or still [AlbumLabels.Looking], which Home holds back meanwhile and shows so once its wait is
 *   over), [year] alone, as the album line had it.
 * Null when there is nothing to say.
 */
internal fun releaseLine(
    album: String,
    year: String?,
    labels: AlbumLabels,
): ReleaseLine? {
    val known = labels as? AlbumLabels.Known ?: NO_LABELS
    val own = known.year
    val line =
        when {
            known.labels.isEmpty() && own == null -> ReleaseLine(emptyList(), albumLineYear(album, year))
            own == null || albumLineYear(album, own) == null -> ReleaseLine(known.labels, null)
            else -> ReleaseLine(known.labels, own, (known.first ?: year)?.takeIf { it < own })
        }
    return line.takeUnless { it.labels.isEmpty() && it.year == null }
}

/**
 * The album in italics, alone on its line, and under it, quieter, [line]: the release's labels, all
 * of them joined by commas, then the year (#729); with none, nothing under it, unless [held], when an
 * empty line keeps its height. One stop for a screen reader, read as a sentence ([releaseSaid]).
 */
@Composable
internal fun ReleaseAbout(
    album: String,
    line: ReleaseLine?,
    held: Boolean,
) {
    val said = line?.let { releaseSaid(album, it) }
    Column(
        Modifier.semantics(mergeDescendants = true) { said?.let { contentDescription = it } },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(withAlbum(ALBUM_MARK, album), style = MaterialTheme.typography.bodyLarge)
        val quiet = MaterialTheme.typography.bodyMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            line != null -> Text(releaseShown(line), Modifier.testTag(RELEASE_LINE_TAG), style = quiet, color = color)
            // Held back until the label is known: a line's height kept, nothing a screen reader stops on.
            held -> Text(" ", Modifier.testTag(RELEASE_HELD_TAG).clearAndSetSemantics {}, style = quiet)
        }
    }
}

/**
 * [line] as shown: "Harvest, Capitol Records · 1973", "Apple Records · 2019 (1969)", or the labels or
 * the year alone, the year with its first in brackets when it has one.
 */
@Composable
private fun releaseShown(line: ReleaseLine): String {
    val labels = line.labels.joinToString(", ").ifEmpty { null }
    val year =
        line.year?.let { own -> line.first?.let { stringResource(R.string.home_album_year_first, own, it) } ?: own }
    return when {
        labels != null && year != null -> stringResource(R.string.home_album_release, labels, year)
        else -> labels ?: year.orEmpty()
    }
}

/**
 * The album and [line] under it as a screen reader reads them: "Rubber Soul, released by Parlophone
 * in 1965.", "Abbey Road, released by Apple Records in 2019, first released in 1969.", "…, released
 * by Parlophone.", "…, released in 1965."; several labels named together, "Harvest and Capitol
 * Records" ([namedInOrder]).
 */
@Composable
private fun releaseSaid(
    album: String,
    line: ReleaseLine,
): String {
    val labels = namedInOrder(line.labels).ifEmpty { null }
    val year = line.year
    val first = line.first
    return when {
        labels != null && year != null && first != null ->
            stringResource(R.string.home_album_released_by_in_first, album, labels, year, first)
        labels != null && year != null -> stringResource(R.string.home_album_released_by_in, album, labels, year)
        labels != null -> stringResource(R.string.home_album_released_by, album, labels)
        first != null -> stringResource(R.string.home_album_released_in_first, album, year.orEmpty(), first)
        else -> stringResource(R.string.home_album_released_in, album, year.orEmpty())
    }
}
