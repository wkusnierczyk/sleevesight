package app.sleevesight.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.ui.theme.PanelRule
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind

// *Search with AI* under the empty state of Home's *Dates*, *Genres* and *Lyrics* (#773): its button,
// its call under way with *Stop*, and what it found, under a rule, credited to the model.

/**
 * What a panel on Home needs for its *Search with AI* (#773): the searches ([feed]), the [track] and
 * the [header] it is shown beside, and whether a search starts by itself ([auto]: *Search with AI
 * when a source has nothing*). The default searches nothing: the panel shows its empty state alone.
 */
internal class PanelSearch(
    val feed: AiSearchFeed = AiSearchFeed.None,
    val track: TrackState? = null,
    val header: NowPlaying? = null,
    val auto: Boolean = false,
)

/**
 * The searches a panel started by itself while it was open (*Search with AI when a source has
 * nothing*, #773), by what each was for: one per opening of the panel, kept above the parts that
 * come and go with its state ([rememberAutoSearch]), so a search starts by itself at most once while
 * it stays open, and never again after *Stop*, a failure, or the panel leaving its empty state and
 * coming back to it, until the panel is opened anew. Not tied to *Dates*, *Genres* or *Lyrics*: a
 * genre with no definition from Wikipedia (#786) starts its search by the same setting through it.
 */
@Stable
internal class AutoSearch {
    // What each search was for; plain, read only as one starts.
    private val started = mutableSetOf<Any>()

    /** Runs [start] unless a search for [key] started already in this opening. */
    fun once(
        key: Any,
        start: () -> Unit,
    ) {
        if (started.add(key)) start()
    }
}

/** An [AutoSearch] for as long as the panel that calls it stays open. */
@Composable
internal fun rememberAutoSearch(): AutoSearch = remember { AutoSearch() }

/**
 * Starts [start] by itself, once ([AutoSearch.once], by [key]), as soon as [on]: the setting is on,
 * and the search is offered (its AI service ready, nothing answered or kept for it, none under way).
 */
@Composable
internal fun AutoSearchStart(
    auto: AutoSearch,
    on: Boolean,
    key: Any,
    start: () -> Unit,
) {
    val current by rememberUpdatedState(start)
    LaunchedEffect(on, key) { if (on) auto.once(key) { current() } }
}

/**
 * *Search with AI* of [kind] under a panel's empty state ([search], #773; `design/design.md`,
 * *Search with AI*), in the panel's column, as [search]'s feed gives it for its track:
 *
 * - offered: an outlined button, as *Why is this …?*, with no AI mark (the plain label was picked),
 *   which a screen reader names "ask your AI service, a paid call"; disabled with no AI service
 *   ready, a screen reader adding why;
 * - under way: "Searching with {model}…" in the panel's quiet note style, *Stop* at the right of the
 *   line, as *Retry* is beside its line ([LineButton]); the focus handed from the button to *Stop*
 *   and back, as each takes the other's place;
 * - answered: a rule, the answer as the panel shows its source's ([answer]), and "by {model}" on a
 *   line of its own, in italics;
 * - nothing usable: a rule, and "{model} found nothing." in the answer's place;
 * - failed: why, in the quiet style, as *Why is this …?*'s failure, and the button again under it.
 *
 * With *Search with AI when a source has nothing* ([PanelSearch.auto]), the search starts by itself
 * once while it is offered, by [auto] (one per opening of the panel). Nothing before the pipeline
 * reaches the track.
 */
@Composable
internal fun AiSearchBlock(
    search: PanelSearch,
    kind: SearchKind,
    auto: AutoSearch,
    answer: @Composable (SearchAnswer, String) -> Unit,
) {
    val track = search.track ?: return
    val feed = search.feed
    // A new subscription only for another track or search, not on every state of the track.
    val state by remember(feed, track.normalized, track.nowPlaying.duration, kind) {
        feed.search(track, kind)
    }.collectAsState(AiSearch.None)
    val handoff = rememberFocusHandoff()
    val button = remember { FocusRequester() }
    val stop = remember { FocusRequester() }
    val ask = {
        feed.ask(track, kind, searchFacts(track, search.header ?: track.nowPlaying))
        handoff.moveTo(stop)
    }
    AutoSearchStart(
        auto,
        search.auto && state == AiSearch.Offered,
        Triple(track.normalized, track.nowPlaying.duration, kind),
    ) {
        feed.ask(track, kind, searchFacts(track, search.header ?: track.nowPlaying))
    }
    val searchButton = Modifier.handoffTarget(handoff, button)
    when (val shown = state) {
        AiSearch.None -> Unit
        AiSearch.Unready -> SearchButton(enabled = false, searchButton) {}
        AiSearch.Offered -> SearchButton(enabled = true, searchButton, ask)
        is AiSearch.Searching ->
            SearchingLine(shown.model, Modifier.handoffTarget(handoff, stop)) {
                feed.stop(track, kind)
                handoff.moveTo(button)
            }
        is AiSearch.Answered -> {
            PanelRule(Modifier.testTag(AI_SEARCH_RULE_TAG))
            answer(shown.answer, shown.model)
            SearchCredit(stringResource(R.string.home_ai_by, shown.model), Modifier.testTag(AI_SEARCH_BY_TAG))
        }
        is AiSearch.NothingFound -> {
            PanelRule(Modifier.testTag(AI_SEARCH_RULE_TAG))
            QuietLine(stringResource(R.string.home_ai_nothing, shown.model), Modifier.testTag(AI_SEARCH_NOTHING_TAG))
        }
        is AiSearch.Failed -> {
            QuietLine(
                stringResource(R.string.home_ai_failed, searchFailureText(shown.reason)),
                Modifier.testTag(AI_SEARCH_FAILED_TAG),
            )
            SearchButton(enabled = true, searchButton, ask)
        }
    }
}

/** "by {model}" under what *Search with AI* found ([text]), on a line of its own, in italics (#773). */
@Composable
internal fun SearchCredit(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * *Search with AI*: outlined, as tall as *Why is this …?* and answering a tap across 48 dp, its action
 * named for a screen reader, which a Material `OutlinedButton` cannot be given; disabled ([enabled]
 * false), dimmed, a screen reader adding that no AI service is enabled.
 */
@Composable
private fun SearchButton(
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CORNER)
    val colours = MaterialTheme.colorScheme
    val ink = if (enabled) colours.primary else colours.onSurface.copy(alpha = DISABLED)
    val edge = if (enabled) colours.outline else colours.onSurface.copy(alpha = DISABLED_EDGE)
    val unready = stringResource(R.string.home_ai_search_unready)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .testTag(AI_SEARCH_TAG)
                .minimumInteractiveComponentSize()
                .heightIn(min = HEIGHT)
                .clip(shape)
                .border(1.dp, edge, shape)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.home_ai_search_action),
                    onClick = onClick,
                ).let { if (enabled) it else it.semantics { stateDescription = unready } }
                .padding(PADDING),
    ) {
        Text(stringResource(R.string.home_ai_search), style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

// "Searching with {model}…" in the quiet style, with *Stop* (under [stopModifier]) at the right of
// the line, centred on it, as *Retry* is beside its line.
@Composable
private fun SearchingLine(
    model: String,
    stopModifier: Modifier,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            QuietLine(stringResource(R.string.home_ai_searching, model), Modifier.testTag(AI_SEARCHING_TAG))
        }
        LineButton(
            stringResource(R.string.home_ai_stop),
            stringResource(R.string.home_ai_stop_action),
            stopModifier.testTag(AI_SEARCH_STOP_TAG),
            onStop,
        )
    }
}

// Why a search failed, in words: the insight's ([failureText]), or that the answer could not be read.
@Composable
private fun searchFailureText(reason: String): String =
    if (reason == AiSearches.MALFORMED) stringResource(R.string.home_failure_malformed) else failureText(reason)

/**
 * What *Search with AI* tells the model about [track] beside [header] (#773, [SearchFacts]): the
 * title and credited artists as cleaned for the sources (as *Lyrics*' Genius search uses them), the
 * album as the player reports it, and what the sources gave the other panels: the album's year as
 * Home shows it ([headerYears]), MusicBrainz's recording when it accepted one, its first release
 * ([firstReleaseOf]), and the genres a source other than MusicBrainz gives (D12: MusicBrainz's tags
 * never reach a prompt). The prompt leaves out the genres when it asks for them.
 */
internal fun searchFacts(
    track: TrackState,
    header: NowPlaying,
): SearchFacts {
    val resolution =
        track.resolution?.takeIf {
            it.recordingMbid != null && it.outcome != Outcome.None && it.outcome !is Outcome.Rejected
        }
    val recording =
        resolution?.let { found ->
            listOfNotNull(found.title, found.artistCredit?.let { "by $it" }).joinToString(" ").ifEmpty { null }
        }
    val genres =
        track.genres.rows
            .flatMap { it.labels }
            .filter { label -> label.credits.any { it.source != LabelSource.MUSICBRAINZ } }
            .map { it.genre.label }
            .distinct()
    return SearchFacts(
        title = track.normalized.title,
        artists = track.normalized.artists,
        album = header.album?.takeIf(String::isNotBlank),
        albumYear = headerYears(track, header).album,
        recording = recording,
        recordingMbid = resolution?.recordingMbid?.takeIf { recording != null },
        firstReleased = firstReleaseOf(track)?.let { first -> "${first.date}, ${first.title}" },
        genres = genres,
    )
}

/**
 * The test tags of *Search with AI* (#773): its button, the line while it searches and its *Stop*,
 * the rule above what it found, the credit under it, the line when it found nothing, and the line
 * when it failed.
 */
const val AI_SEARCH_TAG: String = "ai-search"
const val AI_SEARCHING_TAG: String = "ai-search:searching"
const val AI_SEARCH_STOP_TAG: String = "ai-search:stop"
const val AI_SEARCH_RULE_TAG: String = "ai-search:rule"
const val AI_SEARCH_BY_TAG: String = "ai-search:by"
const val AI_SEARCH_NOTHING_TAG: String = "ai-search:nothing"
const val AI_SEARCH_FAILED_TAG: String = "ai-search:failed"

private val HEIGHT = 32.dp
private val CORNER = 16.dp
private val PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
private const val DISABLED = 0.38f
private const val DISABLED_EDGE = 0.12f
