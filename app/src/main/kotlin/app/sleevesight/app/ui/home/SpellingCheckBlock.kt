package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.SpellingOutcome
import app.sleevesight.app.ui.theme.PanelRule
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind

// The spelling check under "MusicBrainz didn’t find this song." in *Dates* and *Lyrics* (#1293).

/**
 * The spelling check (#1293; `design/design.md`, *Spelling check*) under "MusicBrainz didn’t find this
 * song." in *Dates* and *Lyrics*, in *Ask AI*'s place ([then]) until it has run, as [search]'s feed
 * gives it for its track, [width] wide where the panel's buttons are one column:
 *
 * - offered: *Check the spelling*, with the AI mark, which a screen reader reads as its label and
 *   names "check the spelling on the web with your AI service, a paid call";
 * - under way: the turning record and "Checking the spelling on the web…", with *Stop*;
 * - another spelling found ([Spelled]): the same line, with *Stop*, while MusicBrainz is asked with
 *   it; confirmed, the track is looked up again with it (`SpellingChecks`), the panel then showing
 *   what MusicBrainz found, *Stop* until then cancelling it; not found, a rule, "MusicBrainz didn’t
 *   find it as “{title}” either.", then [then]; stopped, [then];
 * - no other spelling: a rule, "{model} found no other spelling on the web.", then [then]; each line
 *   ending "; asked automatically." for a check that ran by itself, a link to the setting (#956);
 * - failed: as *Ask AI*'s failure, with *Retry*, then [then].
 *
 * [then] alone when no check can be asked (no AI service ready, *AI web search* off) or the track was
 * looked up in another spelling already.
 */
@Composable
internal fun SpellingCheckBlock(
    search: PanelSearch,
    width: Dp = Dp.Unspecified,
    then: @Composable () -> Unit,
) {
    val track = search.track
    if (track == null || track.foundAs != null) return then()
    val feed = search.feed
    val kind = SearchKind.SPELLING
    val texts = SearchTexts.of(kind)
    val state by remember(feed, track.played, track.nowPlaying.duration) {
        feed.search(track, kind)
    }.collectAsState(AiSearch.None)
    val handoff = rememberFocusHandoff()
    val button = remember { FocusRequester() }
    val stop = remember { FocusRequester() }
    val ask = {
        feed.ask(track, kind, spellingFacts(track))
        handoff.moveTo(stop)
    }
    when (val shown = state) {
        AiSearch.None, AiSearch.Unready -> then()
        AiSearch.Offered -> SearchButton(texts, enabled = true, width, Modifier.handoffTarget(handoff, button), ask)
        is AiSearch.Searching ->
            SearchingLine(texts, web = true, Modifier.handoffTarget(handoff, stop)) {
                feed.stop(track, kind)
                handoff.moveTo(button)
            }
        is AiSearch.Answered ->
            Spelled(search, shown, texts, Modifier.handoffTarget(handoff, stop), then)
        is AiSearch.NothingFound -> {
            NothingFound(texts, shown, autoNote(search, kind))
            then()
        }
        is AiSearch.Failed -> {
            RetryLine(
                askFailedText(shown.reason),
                source = "",
                onRetry = { feed.ask(track, kind, spellingFacts(track)) },
                modifier = Modifier.testTag(texts.tag(AI_SEARCH_FAILED_TAG)),
                afterRetry = { handoff.moveTo(stop) },
                action = stringResource(R.string.home_ai_retry_action),
                color = MaterialTheme.colorScheme.error,
            )
            then()
        }
    }
}

// Another spelling found ([shown]): "Checking the spelling on the web…" with *Stop* (under
// [stopModifier]) while MusicBrainz is asked with it, or once confirmed, until the track is looked up
// again, *Stop* then cancelling the question or the spelling's use; not found, a rule and that
// MusicBrainz did not find it either, ending "; asked automatically." for a check that ran by itself
// (#956), then [then]; stopped, [then].
@Suppress("LongParameterList") // The search, its answer, its texts, where *Stop* sits, and what follows.
@Composable
private fun Spelled(
    search: PanelSearch,
    shown: AiSearch.Answered,
    texts: SearchTexts,
    stopModifier: Modifier,
    then: @Composable () -> Unit,
) {
    val feed = search.feed
    val track = search.track ?: return then()
    val spelling = shown.answer as? SearchAnswer.Spelling ?: return then()
    val outcome by remember(feed, track.played, spelling) { feed.spelled(track, spelling) }.collectAsState(null)
    when (outcome) {
        SpellingOutcome.NotFound -> {
            PanelRule(Modifier.testTag(texts.tag(AI_SEARCH_RULE_TAG)))
            val tag = texts.tag(AI_SEARCH_NOTHING_TAG)
            if (shown.way.automatic) {
                val link = autoAskedLink(search.openSettingsAt)
                AutoAskedLine(autoNothingParts(R.string.home_spelling_not_found_auto, spelling.title, link), false, tag)
            } else {
                QuietLine(stringResource(R.string.home_spelling_not_found, spelling.title), Modifier.testTag(tag))
            }
            then()
        }
        SpellingOutcome.Stopped -> then()
        else -> SearchingLine(texts, web = true, stopModifier) { feed.stopSpelling(track, spelling) }
    }
}

/**
 * What the spelling check tells the model about [track] (#1293): the title as the player gives it,
 * its credited artists as named for the sources, and the album as the player reports it; nothing
 * from the sources, which found nothing.
 */
internal fun spellingFacts(track: TrackState): SearchFacts =
    SearchFacts(
        title = track.nowPlaying.title,
        artists = track.played.artists,
        album = track.nowPlaying.album?.takeIf(String::isNotBlank),
    )

/**
 * What [kind]'s search tells the model about [track] beside [header] (the track's own report by
 * default, as `AutoSearches` asks): [spellingFacts] for the spelling check, else [searchFacts].
 */
internal fun panelFacts(
    track: TrackState,
    kind: SearchKind,
    header: NowPlaying? = null,
): SearchFacts =
    if (kind == SearchKind.SPELLING) spellingFacts(track) else searchFacts(track, header ?: track.nowPlaying)

/** The spelling check's test tag of [base], one of *Ask AI*'s (#1293): `ai-search:spelling`, say. */
fun spellingTag(base: String): String = "$base:spelling"
