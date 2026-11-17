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
     * off, did not find the song, or could not be reached); and the release's own [year], when known.
     */
    data class Known(
        val labels: List<String> = emptyList(),
        val year: String? = null,
    ) : AlbumLabels
}

/**
 * The record labels of the release of [header]'s album being played (#729), with [musicBrainzOn] as
 * *Sources* has it, from the tracklists looked up, [states]: the release the back of the cover shows
 * ([tracklistKey], #984), the one the song is matched to, or of several albums of the name the one the
 * listener chose, of [choices] (#1080). While a new song of the album is looked up, the release found
 * for another song of it stands in ([stateOf]), as on the back. Known and empty with MusicBrainz off,
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
        else -> tracklistKey(track, header, choices)?.let { labelsOf(stateOf(it, states)) } ?: NO_LABELS
    }
}

// The labels of a release as its tracklist's lookup, [state], has them.
private fun labelsOf(state: TracklistState?): AlbumLabels =
    when (state) {
        null, TracklistState.Looking -> AlbumLabels.Looking
        is TracklistState.Found ->
            AlbumLabels.Known(state.tracklist.labels, state.tracklist.date?.let(ReleaseDate::yearIn))
        TracklistState.NotFound, TracklistState.Unclear, TracklistState.Failed -> NO_LABELS
    }

private val NO_LABELS = AlbumLabels.Known()

/**
 * What the line under the album says (#729): the [labels] of the release played, and the [year] after
 * them, as the album line used to show it (`albumLineYear`: none when the album's name gives its
 * edition's, #1116).
 */
internal data class ReleaseLine(
    val labels: List<String>,
    val year: String?,
)

/**
 * The line under [album] (#729): the labels of [labels], the release played, and [year], the album's
 * year by [ReleaseDate.albumYear]'s rules (`headerYears`), shown unless the album's name gives its
 * edition's ([albumLineYear]). The label and the year say one release: when the release's own year is
 * known and is not the year shown, the song matched to a later edition than the year says (a bonus
 * track's), the year stands and the labels are left out, the year being the one the album line and
 * *Dates* have always gone by. Labels still [AlbumLabels.Looking] count as none: Home holds the line
 * back meanwhile, and shows this once its wait is over. Null when there is neither.
 */
internal fun releaseLine(
    album: String,
    year: String?,
    labels: AlbumLabels,
): ReleaseLine? {
    val shown = albumLineYear(album, year)
    val known = labels as? AlbumLabels.Known ?: NO_LABELS
    val agree = shown == null || known.year == null || known.year == shown
    val named = known.labels.takeIf { agree }.orEmpty()
    return if (named.isEmpty() && shown == null) null else ReleaseLine(named, shown)
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

/** [line] as shown: "Harvest, Capitol Records · 1973", or the labels or the year alone. */
@Composable
private fun releaseShown(line: ReleaseLine): String {
    val labels = line.labels.joinToString(", ").ifEmpty { null }
    return when {
        labels != null && line.year != null -> stringResource(R.string.home_album_release, labels, line.year)
        else -> labels ?: line.year.orEmpty()
    }
}

/**
 * The album and [line] under it as a screen reader reads them: "Rubber Soul, released by Parlophone
 * in 1965.", "…, released by Parlophone.", "…, released in 1965."; several labels named together,
 * "Harvest and Capitol Records" ([namedInOrder]).
 */
@Composable
private fun releaseSaid(
    album: String,
    line: ReleaseLine,
): String {
    val labels = namedInOrder(line.labels).ifEmpty { null }
    return when {
        labels != null && line.year != null ->
            stringResource(R.string.home_album_released_by_in, album, labels, line.year)
        labels != null -> stringResource(R.string.home_album_released_by, album, labels)
        else -> stringResource(R.string.home_album_released_in, album, line.year.orEmpty())
    }
}
