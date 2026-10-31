package app.sleevesight.app.ui.home

import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.GroundedText

// What *Read aloud* says (#325): the insight's sentences as the card shows them, without their
// citations, and a sentence the sources do not back said to be so.

/**
 * The app's own words in a reading aloud: [unverified], said before a sentence the sources do not
 * back ("Unverified:"), and [trackLine], the track's title and artists ("Sundown, by Gordon
 * Lightfoot."), from `strings_home.xml`.
 */
class SpokenWords(
    val unverified: String,
    val trackLine: (title: String, artists: String) -> String,
)

/**
 * The parts of [grounded] read aloud, one a sentence, in order. Each sentence is read from the
 * grounding check's own sentences and statuses ([GroundedText.paragraphs]), not from the text
 * parsed again: its runs as the card shows them ([shown]), the Markdown cleaned, with the citation
 * runs left out, whatever their id (`[MB1]`, web search's `[S1]` or `[G1]`, the model's own `[M]` or
 * `[AI]`); a sentence the card shows as nothing (a rule on its own) is not read. A sentence the sources do not back
 * ([UNBACKED]: uncited or citing a line that does not exist, both underlined in red, or from the
 * model's own knowledge, with its chip), starts with [SpokenWords.unverified], so listening keeps
 * what the screen marks. A text the check found no sentence in is read whole, as the card shows
 * it, without its citations.
 */
internal fun spokenSentences(
    grounded: GroundedText,
    words: SpokenWords,
): List<String> {
    if (grounded.paragraphs.isEmpty()) {
        val whole = segments(grounded.text).filterIsInstance<Segment.Text>().joinToString("") { it.text }
        return listOfNotNull(tidy(whole).ifEmpty { null })
    }
    return grounded.paragraphs.flatMap { paragraph ->
        paragraph.zip(shown(paragraph.map { it.text })).mapNotNull { (sentence, runs) ->
            val said = tidy(runs.filterIsInstance<ShownRun.Text>().joinToString("") { it.text })
            when {
                said.isEmpty() -> null
                sentence.status in UNBACKED -> "${words.unverified} $said"
                else -> said
            }
        }
    }
}

/** The track line read first, with *Read the track line first* on: [track]'s title and artists, as cleaned up. */
internal fun spokenTrackLine(
    track: TrackState,
    words: SpokenWords,
): String {
    val title = track.normalized.title.ifBlank { track.nowPlaying.title }
    val artists =
        track.normalized.artists
            .joinToString(", ")
            .ifBlank { track.nowPlaying.artists }
    return words.trackLine(title, artists)
}

// Spaces as a voice reads them: runs of white space as one, none before a stop a citation left
// alone ("great [MB1]." is "great ."), none at the ends.
private fun tidy(text: String): String =
    text
        .replace(SPACES, " ")
        .replace(SPACE_BEFORE_STOP, "$1")
        .trim()

private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?…])")
