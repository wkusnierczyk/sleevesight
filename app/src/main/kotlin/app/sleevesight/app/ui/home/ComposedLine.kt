package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.ReleaseDate

// The *Dates* panel's line on when the music was composed (#1132), after the release sentences.

/**
 * When [track]'s music was composed (#1132), after the release sentences ([said]): as MusicBrainz
 * dates it (its result's, else the run's resolution's), else as Wikipedia's result does, from the
 * infobox of the song's article (#1273), else from the song's Wikidata item. None beside no release
 * sentence, since nothing then tells whether it repeats one, and none unless every year of its dates is
 * before the earliest year they say: in that year, the music and the recording are of one date, and the
 * line would only repeat the release.
 */
internal fun composedSentence(
    track: TrackState?,
    said: List<DateSentence>,
): DateSentence.Composed? {
    val wikipedia = (track?.sources?.get(SourceId.WIKIPEDIA) as? SourceState.Done)?.result
    val composed = keptMusicBrainz(track)?.composed ?: track?.resolution?.composed ?: wikipedia?.composed
    val earliest = said.flatMap { it.dates() }.mapNotNull(ReleaseDate::yearIn).minOrNull()
    return composed?.takeIf { earliest != null && it.to < earliest }?.let { DateSentence.Composed(it) }
}

// The dates [this] sentence says, as MusicBrainz gives them: none for a track first released on the
// album or original another sentence dates.
private fun DateSentence.dates(): List<String> =
    when (this) {
        is DateSentence.Album -> listOf(date)
        is DateSentence.Edition ->
            listOfNotNull(
                firstDate,
                when (val part = edition) {
                    is EditionPart.Same -> part.date
                    is EditionPart.Named -> part.date
                    is EditionPart.Remaster -> part.date
                    is EditionPart.Its -> null
                },
            )
        is DateSentence.First -> listOf(date)
        is DateSentence.Original -> listOf(date)
        is DateSentence.FirstOriginal, is DateSentence.FirstHere, is DateSentence.Composed -> emptyList()
    }

/**
 * The name of the source of when the music was composed, for the From line under it: "Wikipedia" for
 * the infobox of the song's article (#1273), not the source's name in the settings, "Wikipedia and
 * Wikidata", since the line links the article; else the source's name ([sourceName]).
 */
@Composable
internal fun composedSourceName(source: SourceId): String =
    if (source == SourceId.WIKIPEDIA) stringResource(R.string.home_source_name_wikipedia) else sourceName(source)
