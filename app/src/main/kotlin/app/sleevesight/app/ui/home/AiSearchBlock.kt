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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.PanelRule
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.lyrics.LyricsLinks
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind

// *Search with AI* under the empty state of Home's *Dates*, *Genres* and *Lyrics* (#773): its button,
// its call under way with *Stop*, and what it found, under a rule, credited to the model.

/**
 * *Search with AI* of [kind] under a panel's empty state ([search], #773; `design/design.md`,
 * *Search with AI*), in the panel's column, as [search]'s feed gives it for its track:
 *
 * - offered: an outlined button, as *Why is this …?*, with no AI mark (the plain label was picked),
 *   which a screen reader names "ask your AI service, a paid call"; disabled with no AI service or
 *   model ready, a screen reader adding why; none at all with *Show insights* off, as *Why is this
 *   …?* has none then;
 * - under way: the turning record, then "Searching with AI…" in the panel's quiet note style ([Busy],
 *   #785), *Stop* at the right of the line, as *Retry* is beside its line ([LineButton]);
 *   the focus handed from the button to *Stop* and back, as each takes the other's place;
 * - answered: a rule, the answer as the panel shows its source's ([answer]), and "by {model}" on a
 *   line of its own, in italics;
 * - nothing usable: a rule, and "{model} found nothing." in the answer's place;
 * - failed: "AI search failed: {reason}." in the error colour, with *Retry* at the right of the line,
 *   as a source that could not be reached has ([RetryLine]), which asks again, a paid call.
 *
 * Nothing before the pipeline reaches the track.
 *
 * Of [SearchKind.DEFINITION] (#786), the search is [genre]'s, by its identity, and asks about its name
 * alone ([SearchFacts.ofGenre]), whatever the track.
 */
@Composable
internal fun AiSearchBlock(
    search: PanelSearch,
    kind: SearchKind,
    genre: GenreLabel? = null,
    answer: @Composable (SearchAnswer, String) -> Unit,
) {
    val track = search.track ?: return
    val feed = search.feed
    val about = genre?.identity
    // A new subscription only for another track (its album too: *Dates*' key has it), genre or
    // search, not on every state of the track.
    val state by remember(feed, track.normalized, track.nowPlaying.duration, track.nowPlaying.album, kind, about) {
        feed.search(track, kind, about)
    }.collectAsState(AiSearch.None)
    val handoff = rememberFocusHandoff()
    val button = remember { FocusRequester() }
    val stop = remember { FocusRequester() }
    val facts = {
        genre?.let {
            SearchFacts.ofGenre(
                it.label,
            )
        } ?: searchFacts(track, search.header ?: track.nowPlaying)
    }
    val ask = {
        feed.ask(track, kind, facts(), genre = about)
        handoff.moveTo(stop)
    }
    val searchButton = Modifier.handoffTarget(handoff, button)
    when (val shown = state) {
        AiSearch.None -> Unit
        AiSearch.Unready -> SearchButton(enabled = false, searchButton) {}
        AiSearch.Offered -> SearchButton(enabled = true, searchButton, ask)
        AiSearch.Searching ->
            SearchingLine(Modifier.handoffTarget(handoff, stop)) {
                feed.stop(track, kind, about)
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
        is AiSearch.Failed ->
            RetryLine(
                stringResource(R.string.home_ai_failed, searchFailureText(shown.reason)),
                source = "",
                onRetry = { feed.ask(track, kind, facts(), genre = about) },
                modifier = Modifier.testTag(AI_SEARCH_FAILED_TAG),
                afterRetry = { handoff.moveTo(stop) },
                action = stringResource(R.string.home_ai_retry_action),
                color = MaterialTheme.colorScheme.error,
            )
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

// The turning record and "Searching with AI…" in the quiet style ([Busy]), with *Stop* (under
// [stopModifier]) at the right of the line, centred on it, as *Retry* is beside its line.
@Composable
private fun SearchingLine(
    stopModifier: Modifier,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Busy(
            stringResource(R.string.home_ai_searching),
            MaterialTheme.typography.bodySmall,
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textModifier = Modifier.testTag(AI_SEARCHING_TAG),
        )
        LineButton(
            stringResource(R.string.home_ai_stop),
            stringResource(R.string.home_ai_stop_action),
            stopModifier.testTag(AI_SEARCH_STOP_TAG),
            onClick = onStop,
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
 * The panels on Home whose *Search with AI* (#773) would show for [track] under [settings], for the
 * setting to start by itself (`AutoSearches`): none until every source has answered and no panel is
 * still looking (MusicBrainz, the genres gathered and assembled, the label requests after the
 * evidence answered); then *Dates* with no date, *Genres* with no genre (while *Show genres* is on)
 * and *Lyrics* with no page (while *Lyrics links* is on; not for an instrumental), as each panel
 * tells its empty states ([NoDates], [GenresShown], [homeLyrics]), beside the header the pipeline's
 * track reports.
 */
internal fun emptyPanels(
    track: TrackState,
    settings: Settings,
): Set<SearchKind> {
    val genresLooking =
        settings.showGenres && (!track.genresAssembled || GenresShown.of(track, settings).looking.isNotEmpty())
    if (!track.gathered || genresLooking || NoDates.of(track) == NoDates.LOOKING) return emptySet()
    return buildSet {
        if (homeDates(track, track.nowPlaying).isEmpty()) add(SearchKind.DATES)
        if (settings.showGenres && track.genres.isEmpty) add(SearchKind.GENRES)
        if (settings.lyricsLinks && homeLyrics(track, settings.lyricsSearch) is LyricsLinks.NoLink) {
            add(SearchKind.LYRICS)
        }
    }
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
