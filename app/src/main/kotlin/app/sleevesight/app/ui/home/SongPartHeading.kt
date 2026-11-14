package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SongParts

/**
 * The heading of a part of the song's insight in two parts (#1133, [SongParts]; the D-Classical
 * mock-up's pick): *The music* or *This recording*, bold, in the insight's own size, a little apart
 * from what comes before it, read by a screen reader as a heading.
 */
@Composable
internal fun SongPartHeading(heading: String) {
    val text =
        stringResource(
            if (heading == SongParts.MUSIC) R.string.home_song_part_music else R.string.home_song_part_recording,
        )
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = HEADING_GAP).testTag(songPartTag(heading)).semantics { heading() },
    )
}

/**
 * An insight's checked [grounded] paragraphs, each as [CheckedProse] shows it, citing [evidence] by
 * [label], [onCite] on a citation's tap; a part's heading ([CheckedSentence.heading], #1133) as one.
 */
@Composable
internal fun CheckedParagraphs(
    grounded: GroundedText,
    evidence: EvidenceSet?,
    onCite: (String) -> Unit,
    label: (String) -> String,
) {
    for (paragraph in grounded.paragraphs) {
        val heading = partHeading(paragraph)
        if (heading != null) {
            SongPartHeading(heading)
        } else {
            CheckedProse(paragraph, grounded, evidence, onCite, label = label)
        }
    }
}

/**
 * The paragraphs of [partial], an insight still being written, each as [UncheckedProse] shows it; with
 * [parts] (the song's, #1133), a line that is a part's heading as one ([SongParts.separated]).
 */
@Composable
internal fun UncheckedParagraphs(
    parts: Boolean,
    partial: String,
    prose: InsightContext,
    onCite: (String) -> Unit,
    label: (String) -> String,
) {
    for (paragraph in paragraphsOf(if (parts) SongParts.separated(partial) else partial)) {
        val heading = if (parts) SongParts.heading(paragraph) else null
        if (heading != null) SongPartHeading(heading) else UncheckedProse(paragraph, prose, onCite, label = label)
    }
}

/** The heading a checked [paragraph] is, when it is one ([CheckedSentence.heading]); null otherwise. */
internal fun partHeading(paragraph: List<CheckedSentence>): String? =
    paragraph.singleOrNull()?.takeIf { it.heading }?.text

/** The test tag of the song's part [heading] ([SongParts.HEADINGS]). */
internal fun songPartTag(heading: String): String = "song-part:$heading"

// Added to the panel's space between paragraphs above a heading, as the mock-up has it.
private val HEADING_GAP = 4.dp
