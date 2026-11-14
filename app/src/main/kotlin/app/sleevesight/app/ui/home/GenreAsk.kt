package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

/**
 * *Is this …?* (#1117), for a genre only MusicBrainz gives that the AI's genre list leaves out, once
 * its *Why is this …?* is tapped on this play ([GenreReason.Ask]): the button, enabled, with the AI
 * mark, [question] its text, a tap asking the AI service ([onClick]); under it, in the panel's quiet
 * note style, why this genre is asked so. A block of its own, so a line about the genre can go
 * above it.
 */
@Composable
internal fun AskBlock(
    question: String,
    onClick: () -> Unit,
) {
    Column(Modifier.testTag(GENRE_ASK_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WhyButton(question, onClick = onClick)
        QuietNote(stringResource(R.string.genre_ask_only_musicbrainz))
    }
}

/** The test tag of *Is this …?* with the line under it (#1117, [AskBlock]). */
const val GENRE_ASK_TAG: String = "genre-panel:ask"
