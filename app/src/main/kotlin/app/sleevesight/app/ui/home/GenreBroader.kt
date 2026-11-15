package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.pipeline.TrackState

// A genre's broader genre in its panel (#1127): named under the genre's definition, and opened in
// its place.

/**
 * Under [named]'s definition, in the panel's quiet note style (#1127): "Contemporary R&B is a style
 * of R&B.", [broader] a link that opens its definition in the genre's place ([onOpen], [BroaderAbout]).
 */
@Composable
internal fun BroaderLine(
    named: GenreLabel,
    broader: GenreLabel,
    onOpen: () -> Unit,
) {
    LinkedText(
        linkedParts(
            stringResource(R.string.genre_broader),
            TextPart.Words(named.label),
            TextPart.Action(inSentence(broader.label), GENRE_BROADER_LINK_TAG, onOpen),
        ),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(GENRE_BROADER_TAG),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * [broader], the broader genre of [named], [asked]'s genre, in [named]'s place (#1127): "Back to
 * {genre}" ([onBack]) above, then its definition ([GenreDefinitionNote]), then *Why is this …?* for
 * it where the notes offer it ([GenreReasonBlock]): only where a source other than MusicBrainz gives
 * [named] or [broader] itself (`GenreReasons.allowed`), never for one only MusicBrainz's tags lead
 * to. No *Search with AI* for a definition Wikipedia lacks: the genre's name would reach the AI
 * service whatever led to it.
 */
@Suppress("LongParameterList") // The track, both genres, the notes, and where the focus and *Back* go.
@Composable
internal fun BroaderAbout(
    asked: TrackState,
    named: GenreLabel,
    broader: GenreLabel,
    shown: PanelNotes,
    afterRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val notes = shown.notes
    val reason by remember(
        asked.normalized,
        asked.nowPlaying.duration,
        asked.nowPlaying.play,
        asked.evidence?.fingerprint,
        asked.genres,
        broader.identity,
        notes,
    ) {
        notes.reason(asked, broader.identity)
    }.collectAsState(GenreReason.None)
    LinkedText(
        // The whole line the link, the larger target; the genre's label is the vocabulary's own.
        listOf(
            TextPart.Action(stringResource(R.string.genre_broader_back, named.label), GENRE_BROADER_BACK_TAG, onBack),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GenreDefinitionNote(broader, notes, afterRetry = afterRetry)
    GenreReasonBlock(reason, asked, broader, shown, afterRetry)
}

/** The test tags of the line naming a genre's broader genre, its link, and the link back (#1127). */
const val GENRE_BROADER_TAG: String = "genre-panel:broader"
const val GENRE_BROADER_LINK_TAG: String = "genre-panel:broader-link"
const val GENRE_BROADER_BACK_TAG: String = "genre-panel:broader-back"
