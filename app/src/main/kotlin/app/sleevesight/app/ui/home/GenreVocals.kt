package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.VocalsShown
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.LaterLabels
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.pipeline.wantsLibrary
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.sameAddress
import kotlin.time.Instant

// The check of a track's vocals (#998; `design/design.md`, *Genre labels*): its answer weighed into the
// genres, what starts it by itself, and its lines under Instrumental's definition.

/**
 * What *Ask AI automatically*'s check of the track's vocals follows (#998), on Home whether *Genres* is
 * open or not: [playing]'s genres composed as the panel composes them ([composedGenres]), told to the
 * notes as they change ([GenresSettled]). The check is started, and started again once its gates open,
 * by its owner (`AutoSearches`, in `AppGraph`), not here. Nothing with the setting off, or *AI web
 * search* off as used.
 */
@Composable
internal fun AutoVocalsCheck(
    playing: TrackState?,
    settings: Settings?,
    notes: PanelNotes,
    search: PanelSearch,
) {
    if (settings?.searchWithAi != true || settings.aiWebSearchUsed == AiWebSearch.OFF) return
    GenresSettled(playing, composedGenres(playing, notes, search).track, settings, notes.notes)
}

/**
 * Tells [notes] [composed], [playing]'s genres as *Genres* composes them, and whether the free steps
 * are done with them ([GenreNotes.genresShown], #998): the run's labels in, none still asked, and
 * LRCLIB done with them ([libraryPending]: it comes first, and its answer may settle it for free).
 */
@Composable
internal fun GenresSettled(
    playing: TrackState?,
    composed: TrackState?,
    settings: Settings?,
    notes: GenreNotes,
) {
    val settled =
        settings != null &&
            playing != null &&
            composed != null &&
            playing.gathered &&
            playing.genresAssembled &&
            LaterLabels.ASKING !in playing.laterLabels.values &&
            !libraryPending(composed, settings)
    LaunchedEffect(composed, settled) {
        if (composed != null) notes.genresShown(composed, settled)
    }
}

/**
 * Whether LRCLIB's answer for [track]'s Instrumental is still to come (#997): on under *Sources* in
 * [settings], a query it can be asked ([LibraryQuery.of]), the genres calling for it, and it not
 * found unreachable.
 */
internal fun libraryPending(
    track: TrackState,
    settings: Settings,
): Boolean =
    settings.isEnabled(SourceId.LRCLIB) &&
        LibraryQuery.of(track.normalized, track.nowPlaying) != null &&
        track.genres.wantsLibrary() &&
        !track.libraryUnreachable

/**
 * Under Instrumental's definition (#998), the check of the track's vocals as [shown] has it: while it
 * runs, the small turning record and "Checking whether this track has vocals…"; found instrumental,
 * "Web search found this track described as instrumental." and the citation of its page; after a tap
 * on this play, vocals found, "Web search found vocals on this track, so it is not *Instrumental*."
 * ([genre] in italics, as its chip names it) and its page's citation, or nothing found either way,
 * "Web search found nothing on whether this track has vocals."; each answer with "by {model}, with
 * web search" under it. The page's citation is the line *Sources* shows it under ([lines], the
 * same address), else numbered after them, a link that opens it there ([onCite]). Nothing with no
 * check, or a failed one.
 */
@Composable
internal fun VocalsNote(
    shown: VocalsShown?,
    genre: GenreLabel,
    lines: EvidenceSet?,
    onCite: (String) -> Unit,
) {
    when (val state = shown?.state) {
        VocalsState.Checking ->
            Busy(
                stringResource(R.string.genre_vocals_checking),
                MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textModifier = Modifier.testTag(GENRE_VOCALS_CHECKING_TAG),
            )
        is VocalsState.Checked -> {
            val words =
                when (state.answer) {
                    VocalsAnswer.INSTRUMENTAL ->
                        listOf(
                            TextPart.Words(stringResource(R.string.genre_vocals_instrumental)),
                        )
                    VocalsAnswer.VOCALS ->
                        genreParts(
                            stringResource(R.string.genre_vocals_found, ALBUM_MARK),
                            genre.label,
                        )
                    VocalsAnswer.UNKNOWN -> listOf(TextPart.Words(stringResource(R.string.genre_vocals_unknown)))
                }.takeIf { state.answer == VocalsAnswer.INSTRUMENTAL || shown.tapped } ?: return
            Column(Modifier.testTag(GENRE_VOCALS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val line = state.page?.let { lineOf(it, lines) }
                AnswerLine(words, line, onCite)
                SearchCredit(stringResource(R.string.genre_why_by_web, shown.model))
            }
        }
        is VocalsState.Failed, null -> Unit
    }
}

/**
 * Whether [shown], the check of the track's vocals, says in place of the weak genre's line why it is
 * weak (#998): answered vocals or nothing found, after a tap on this play.
 */
internal fun answersWeak(shown: VocalsShown?): Boolean {
    val answer = (shown?.state as? VocalsState.Checked)?.answer
    return shown?.tapped == true && (answer == VocalsAnswer.VOCALS || answer == VocalsAnswer.UNKNOWN)
}

/**
 * Whether [shown], the check of the track's vocals asked by a tap on this play, found vocals (#998):
 * *Why is this instrumental?* is then gone for the play, and no reason written.
 */
internal fun vocalsTapped(shown: VocalsShown?): Boolean =
    shown?.tapped == true && (shown.state as? VocalsState.Checked)?.answer == VocalsAnswer.VOCALS

// [format] with [genre] in italics where it has [ALBUM_MARK].
private fun genreParts(
    format: String,
    genre: String,
): List<TextPart> {
    val at = format.indexOf(ALBUM_MARK)
    if (at < 0) return listOf(TextPart.Words(format))
    return listOf(
        TextPart.Words(format.substring(0, at)),
        TextPart.Styled(genre, SpanStyle(fontStyle = FontStyle.Italic)),
        TextPart.Words(format.substring(at + ALBUM_MARK.length)),
    )
}

// [words] in the panel's quiet note style, the citation of [line] after them when it has one, read
// as #1000's notes are: the text, then "(Source S12)", the link offered as an action too.
@Composable
private fun AnswerLine(
    words: List<TextPart>,
    line: String?,
    onCite: (String) -> Unit,
) {
    val text = words.joinToString("") { it.text }
    val cite = line?.let { stringResource(R.string.home_cite, it) }
    val parts = if (line == null) words else words + TextPart.Words(" ") + citation(line, onCite)
    LinkedText(
        parts,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.semantics {
                contentDescription = if (cite == null) text else "$text ($cite)"
                if (line != null && cite != null) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(cite) {
                                onCite(line)
                                true
                            },
                        )
                }
            },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The id of [page]'s line: the web line [lines] (*Sources*' lines) has at its address, else the one it
// takes numbered after them.
private fun lineOf(
    page: WebPage,
    lines: EvidenceSet?,
): String? {
    val shown = lines ?: return null
    val same =
        shown.lines.firstOrNull {
            it.source == SourceId.WEB &&
                it.url?.let { url ->
                    sameAddress(url, page.url)
                } == true
        }
    return same?.id ?: numbered(page, shown)
}

// The id [page] takes as a web line numbered after [shown]'s.
private fun numbered(
    page: WebPage,
    shown: EvidenceSet,
): String? {
    val draft = EvidenceDraft(SourceId.WEB, Scope.WEB, page.text, page.url, Instant.fromEpochMilliseconds(0))
    val own = shown.ids.toSet()
    return shown
        .with(listOf(draft), JvmHasher)
        .lines
        .firstOrNull { it.id !in own && it.url == page.url }
        ?.id
}

/** The test tags of the check's line while it runs, and of its answer with its credit (#998). */
const val GENRE_VOCALS_CHECKING_TAG: String = "genre-panel:vocals-checking"
const val GENRE_VOCALS_TAG: String = "genre-panel:vocals"
