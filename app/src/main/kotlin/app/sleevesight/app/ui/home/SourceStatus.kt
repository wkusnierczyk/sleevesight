package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.sleevesight.app.R
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome

// What a source's row in *Sources*' table says it found (#662), and what each source found as a
// list (#768), in words the resources give.

/**
 * What [chip]'s row in *Sources*' table says it found (#662), from its own lines among [lines]
 * ([finding], #768), in the resources' words.
 */
@Suppress("SpreadOperator") // A handful of arguments, passed straight through.
@Composable
internal fun statusText(
    chip: SourceChip,
    lines: List<EvidenceLine>,
    details: Boolean,
): String {
    val resources = LocalResources.current
    return statusText(chip.status, details, finding(chip, lines)) { id, args -> resources.getString(id, *args) }
}

/**
 * What a source's row in *Sources*' table says it found (#662), in [words]: a lookup that found
 * something, what it found ([finding], #768: *Found the track and the artist*, *Found pages about
 * the track*); any other status as it is. The statuses that are diagnostics show only with
 * grounding [details]: a match rejected, outside them, is nothing found, and a finding kept from an
 * earlier lookup says so ("Found the track (kept)") only with them.
 */
internal fun statusText(
    status: ChipStatus,
    details: Boolean,
    finding: Finding?,
    words: Words,
): String =
    when (status) {
        ChipStatus.Release -> words.get(R.string.home_source_release)
        ChipStatus.Pending -> words.get(R.string.home_source_pending)
        ChipStatus.Failed -> words.get(R.string.home_source_failed)
        ChipStatus.NeedsKey -> words.get(R.string.home_source_needs_key)
        ChipStatus.ModelAllowed -> words.get(R.string.home_source_model_allowed)
        is ChipStatus.Done -> {
            val found = finding?.let { findingText(it, words) } ?: outcomeText(status.outcome, details, words)
            if (status.cached && details) words.get(R.string.home_source_cached, found) else found
        }
    }

/**
 * [finding] in [words] (#768): "Found" and its list (*Found the track, the original song and the
 * artist*), or web search's *Found pages about the track*.
 */
internal fun findingText(
    finding: Finding,
    words: Words,
): String =
    when (finding) {
        Finding.Pages -> words.get(R.string.home_source_found_pages)
        is Finding.Of ->
            words.get(
                R.string.home_source_found,
                listText(finding.scopes.map { findingWord(it, words) }, words),
            )
    }

/** What a thing found is called in a finding's list (#768). */
private fun findingWord(
    scope: Scope,
    words: Words,
): String =
    words.get(
        when (scope) {
            Scope.ORIGINAL_SONG -> R.string.home_source_finding_original_song
            Scope.ARTIST -> R.string.home_source_finding_artist
            // The finding's list has none of the others (#768); the track for any.
            Scope.SONG, Scope.RELEASE, Scope.WEB -> R.string.home_source_finding_track
        },
    )

/**
 * [items] as a list in [words] (#768): one alone; the last after the others with "and" ("%1$s and
 * %2$s"), the others before it with commas ("%1$s, %2$s"), with no comma before the "and".
 */
internal fun listText(
    items: List<String>,
    words: Words,
): String =
    if (items.size < 2) {
        items.firstOrNull().orEmpty()
    } else {
        val comma = { list: String, item: String -> words.get(R.string.home_source_finding_comma, list, item) }
        val others = items.dropLast(1).reduce(comma)
        words.get(R.string.home_source_finding_and, others, items.last())
    }

/** What a lookup's [outcome] says alone: for one that found something, what (#768: *Found the track*). */
private fun outcomeText(
    outcome: Outcome,
    details: Boolean,
    words: Words,
): String =
    when (outcome) {
        Outcome.SongArticle -> findingText(Finding.Of(listOf(Scope.SONG)), words)
        Outcome.ArtistArticle -> findingText(Finding.Of(listOf(Scope.ARTIST)), words)
        Outcome.OriginalSong -> findingText(Finding.Of(listOf(Scope.ORIGINAL_SONG)), words)
        is Outcome.Rejected -> words.get(if (details) R.string.home_source_rejected else R.string.home_source_none)
        Outcome.None -> words.get(R.string.home_source_none)
        Outcome.SourceOff -> words.get(R.string.home_source_off)
        Outcome.Unreachable -> words.get(R.string.home_source_unreachable)
    }
