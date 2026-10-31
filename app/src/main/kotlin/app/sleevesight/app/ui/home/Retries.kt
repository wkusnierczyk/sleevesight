package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey

// *Retry* beside a source that could not be reached (#724): the line that offers it, and what a tap
// on it asks. For any panel on Home whose source did not answer: *Dates*' MusicBrainz, and the
// lyrics' (#728) and the genres' (#772) to come.

/**
 * Where a tap on *Retry* goes, from the pipeline's [view] beside the track [playing]: [of] a source
 * is the tap that asks it alone again ([PipelineView.askAgainKey], free, as back online asks every
 * source, #192), or null while the pipeline does not offer it (the sources not landed yet, a model
 * call under way, or a source that did not end unreachable). [askAgain] carries the tap to the
 * pipeline (`PipelineHost.askAgain`), with the key it was offered under and the track it was shown
 * beside, as Write's does.
 */
internal class Retries(
    private val view: PipelineView,
    private val playing: NowPlaying,
    private val askAgain: (RunKey, SourceId, NowPlaying) -> Unit,
) {
    fun of(source: SourceId): (() -> Unit)? {
        val key = view.askAgainKey?.takeIf { source in view.askable } ?: return null
        return { askAgain(key, source, playing) }
    }
}

/**
 * [message], that [source] (its name, as the screen reader's action names it) could not be reached,
 * with *Retry* on the same line at its right, centred on the text, which [onRetry] runs; without
 * [onRetry] (the pipeline does not offer it now), the message alone. A screen reader reads *Retry*
 * as a button whose action is "ask MusicBrainz again" ("Double-tap to ask MusicBrainz again").
 *
 * A tap then runs [afterRetry]: the source asked again, the line gives way to the panel's "Looking
 * on …", and *Retry*, holding the focus, goes, so the caller hands the focus on (to its panel's
 * *Close*, #724), or a keyboard's or a screen reader's would fall to the top of the screen.
 */
@Composable
internal fun RetryLine(
    message: String,
    source: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    afterRetry: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            // A text button's look, with the action named for a screen reader, which a Material
            // `TextButton` cannot be given.
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .testTag(RETRY_TAG)
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.home_retry_action, source),
                            onClick = {
                                onRetry()
                                afterRetry()
                            },
                        ).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.home_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The test tag of *Retry* ([RetryLine]). */
const val RETRY_TAG: String = "retry"
