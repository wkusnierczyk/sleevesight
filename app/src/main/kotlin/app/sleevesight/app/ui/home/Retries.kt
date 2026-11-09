package app.sleevesight.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.theme.LocalPanelEndReach
import app.sleevesight.app.ui.theme.reachPanelEnd
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState

// *Retry* beside a source that could not be reached (#724): the line that offers it, and what a tap
// on it asks. For any panel on Home whose source did not answer: *Dates*' MusicBrainz, and *Lyrics*'
// (#728), also when its lyrics links are not known; the genres' (#772); and where what is shown may be
// partial for it (#1121): under the insight, in *Genres*, and in *Sources*' note.

/**
 * Where a tap on *Retry* goes, from the pipeline's [view] beside the track [playing]: [of] a source
 * is the tap that asks it alone again ([PipelineView.askAgainKey], free, as back online asks every
 * source, #192), or null while the pipeline does not offer it (the sources not landed yet, a model
 * call under way, or a source that did not end unreachable). [ofAll] some sources, named on one
 * line (#1121: "Last.fm and MusicBrainz could not be reached; …"), is the tap that asks those of them
 * it offers again, together, or null while it offers none. [askAgain] carries the tap to the
 * pipeline (`PipelineHost.askAgain`), with the key it was offered under and the track it was shown
 * beside, as Write's does.
 */
internal class Retries(
    private val view: PipelineView,
    private val playing: NowPlaying,
    private val askAgain: (RunKey, Set<SourceId>, NowPlaying) -> Unit,
) {
    fun of(source: SourceId): (() -> Unit)? = ofAll(listOf(source))

    /** The sources the insight draws on that did not answer for [track] ([insightUnanswered]), and their *Retry*. */
    fun forInsight(track: TrackState?): Unanswered? =
        insightUnanswered(track).takeIf { it.isNotEmpty() }?.let { Unanswered(it, ofAll(it)) }

    fun ofAll(sources: Collection<SourceId>): (() -> Unit)? {
        val key = view.askAgainKey
        val asked = sources.filterTo(mutableSetOf()) { it in view.askable }
        return if (key == null || asked.isEmpty()) null else ({ askAgain(key, asked, playing) })
    }
}

/**
 * [sources] that did not answer (#1121), named on one line, with the tap on *Retry* beside it that
 * asks them again ([retry], null while the pipeline does not offer it).
 */
internal class Unanswered(
    val sources: List<SourceId>,
    val retry: (() -> Unit)?,
)

/**
 * [names] said together, in alphabetical order, as one line names the sources that did not answer
 * (#1121): "MusicBrainz", "Last.fm and MusicBrainz" ([R.string.home_sources_two]), and so on for more.
 */
@Composable
internal fun namedTogether(names: List<String>): String {
    val sorted = names.distinct().sorted()
    return sorted.drop(1).fold(sorted.first()) { all, next -> stringResource(R.string.home_sources_two, all, next) }
}

/**
 * The sources the insight draws on that did not answer for [track] (#1121): those whose lookup ended
 * unreachable, in its sources' order, but LRCLIB, which gives no evidence line. Empty for none.
 */
internal fun insightUnanswered(track: TrackState?): List<SourceId> =
    track
        ?.sources
        ?.filter { (source, state) ->
            source != SourceId.LRCLIB && (state as? SourceState.Done)?.result?.outcome == Outcome.Unreachable
        }?.keys
        ?.toList()
        .orEmpty()

/**
 * [message], that [source] (its name, as the screen reader's action names it) could not be reached,
 * with *Retry* on the same line at its right, centred on the text, which [onRetry] runs; without
 * [onRetry] (the pipeline does not offer it now), the message alone. A screen reader reads *Retry*
 * as a button whose action is "ask MusicBrainz again" ("Double-tap to ask MusicBrainz again").
 *
 * [action] names *Retry*'s action otherwise, and [color] colours the message: *Search with AI*'s
 * failure (#773), "ask your AI service again, a paid call", in the error colour. [style] sets the
 * message otherwise, and [outlined] draws *Retry* outlined: a genre's definition that Wikipedia did
 * not give (#786), in the panel's quiet note style. [ai] gives *Retry* the **AI** mark, where it
 * calls the AI service ([LineButton]).
 *
 * [tag] is *Retry*'s test tag ([RETRY_TAG] unless given).
 *
 * A tap then runs [afterRetry]: the source asked again, the line gives way to the panel's "Looking
 * on …", and *Retry*, holding the focus, goes, so the caller hands the focus on (to its panel's
 * *Close*, #724), or a keyboard's or a screen reader's would fall to the top of the screen.
 */
@Suppress("LongParameterList") // The line, its button, and how each reads.
@Composable
internal fun RetryLine(
    message: String,
    source: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    afterRetry: () -> Unit = {},
    action: String = stringResource(R.string.home_retry_action, source),
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    outlined: Boolean = false,
    ai: Boolean = false,
    tag: String = RETRY_TAG,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = style, color = color, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            LineButton(
                stringResource(R.string.home_retry),
                action,
                Modifier.testTag(tag),
                outlined,
                ai,
            ) {
                onRetry()
                afterRetry()
            }
        }
    }
}

/**
 * A button at the end of a line, as *Retry* is ([RetryLine]) and *Search with AI*'s *Stop* (#773):
 * a text button's look, [text] in the primary colour, with the action named for a screen reader
 * ([action]: "Double-tap to …"), which a Material `TextButton` cannot be given; at least 48 dp to
 * touch; in a panel with *Close*, it ends its label where *Close* ends its own ([reachPanelEnd],
 * #1068). [modifier] goes on the button (a test tag, a focus requester). [outlined], with an
 * outline, as *Search with AI* is (#786: *Retry* under a genre's failed definition), whose edge is
 * the outline, so it keeps to the line's end, with the panel's rule. [ai], with the
 * **AI** mark ([AiLabel]), for a tap that calls the AI service (#624: *Retry* under a song's, an
 * artist's or an album's insight that failed, #522).
 */
@Suppress("LongParameterList") // The label, its action, how it looks, and its tap.
@Composable
internal fun LineButton(
    text: String,
    action: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    ai: Boolean = false,
    onClick: () -> Unit,
) {
    val edge = MaterialTheme.colorScheme.outline
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .reachPanelEnd(if (outlined) 0.dp else LocalPanelEndReach.current)
                .then(modifier)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .let { if (outlined) it.border(1.dp, edge, CircleShape) else it }
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = if (outlined) 6.dp else 8.dp),
    ) {
        if (ai) {
            // A tap that calls the AI service (#624): the mark after the label, read "Retry, uses AI".
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.primary,
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) { AiLabel(text) }
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Under the insight's text, while sources it draws on did not answer (#1121): that some evidence for
 * it may be missing, naming them, quiet as the footer's lines, with *Retry* beside it, which asks them
 * again; the line goes once they answer.
 */
@Composable
internal fun InsightPartial(unanswered: Unanswered) {
    val names = namedTogether(unanswered.sources.map { sourceName(it) })
    RetryLine(
        stringResource(R.string.home_insight_partial, names),
        names,
        unanswered.retry,
        Modifier.testTag(INSIGHT_PARTIAL_TAG),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        tag = INSIGHT_RETRY_TAG,
    )
}

/** The test tag of *Retry* ([RetryLine]). */
const val RETRY_TAG: String = "retry"

/**
 * The test tags of the line under the insight while a source it draws on did not answer, and of its
 * *Retry* (#1121, [InsightPartial]).
 */
const val INSIGHT_PARTIAL_TAG: String = "insight-partial"
const val INSIGHT_RETRY_TAG: String = "insight-partial:retry"
