package app.sleevesight.app.ui.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.sleevesight.app.R
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.pipeline.TrackState

// A genre of weak evidence (#875; `design/design.md`, *Genre labels*): its chip's mark, and the line
// in its body that says why.

/**
 * A weak genre's name on its chip (#875): in the notes' colour and in italics, as an unbacked
 * sentence in an insight (#819), at the regular weight; while [selected], in italics, dimmed on the
 * selected fill: the chip's text colour at [WEAK_SELECTED] over it. A screen reader reads it "Country,
 * weak evidence", or, for a genre an artist's description names ([spoken], #1000: "Instrumental,
 * from the artist’s description"), that, then "weak evidence".
 */
@Composable
internal fun WeakName(
    label: String,
    selected: Boolean,
    spoken: String = label,
) {
    val described = stringResource(R.string.genre_weak, spoken)
    Text(
        label,
        modifier = Modifier.semantics { contentDescription = described },
        color = weakColour(selected),
        style = weakStyle(),
    )
}

/**
 * A weak genre's name on its chip that the AI's genre list added (#996: Instrumental on a sung
 * track), as [WeakName] draws a weak one, with the **AI** mark after it ([AiLabel]); a screen reader
 * reads it "Instrumental, from Claude Haiku 5.5, weak evidence".
 */
@Composable
internal fun WeakAiName(
    label: String,
    model: String,
    selected: Boolean,
) {
    val described = stringResource(R.string.genre_weak, stringResource(R.string.genre_ai_added, label, model))
    CompositionLocalProvider(LocalContentColor provides weakColour(selected)) {
        ProvideTextStyle(weakStyle()) { AiLabel(label, described = described) }
    }
}

// A weak name's colour on its chip: the notes' colour; [selected], the chip's text colour dimmed.
@Composable
private fun weakColour(selected: Boolean): Color =
    if (selected) {
        LocalContentColor.current.copy(alpha = WEAK_SELECTED)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

// A weak name's style on its chip: the chip's, in italics, at the regular weight.
@Composable
private fun weakStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)

// The selected weak chip's text, the chip's text colour at this alpha over the selected fill: dimmed
// from about 15:1 and 12.6:1 against it, yet above 4.5:1, in Material's baseline light and dark
// schemes (about 8.2:1 and 5.6:1).
private const val WEAK_SELECTED = 0.72f

/**
 * Why [genre] is of weak evidence for [track] (#875, [WeakEvidence]), under its definition's line
 * (or what stands in its place) and before *Why is this …?*, in the panel's quiet note style, the
 * genre in italics as its chip names it: "Few MusicBrainz contributors tagged this track *Country*,
 * and the artist's tags don't include it."; for Instrumental on a track with signs of vocals (#996),
 * the sign: the vocalist MusicBrainz credits, the lyricist of its work (#998), the language of the
 * lyrics, or the featured artist;
 * for Instrumental only the artist's description gives (#1000, #998's artist hint), what it says,
 * quoted, and the citation of its line, a link to it in *Sources* ([onCite]);
 * for Instrumental weak as LRCLIB's mark alone (#1055, #1232), that only LRCLIB's user-submitted
 * records mark the track so. Nothing for a genre that is not weak.
 */
@Composable
internal fun WeakEvidenceNote(
    track: TrackState,
    genre: GenreLabel,
    onCite: (String) -> Unit = {},
) {
    WeakEvidenceNote(track.genres.weakEvidence(genre.identity), genre, onCite)
}

/** The line [WeakEvidenceNote] shows for [genre], weak for the reason [why]; nothing for none. */
@Composable
internal fun WeakEvidenceNote(
    why: WeakEvidence?,
    genre: GenreLabel,
    onCite: (String) -> Unit = {},
) {
    when (why) {
        null -> Unit
        is WeakEvidence.ArtistHint ->
            CitedNote(stringResource(R.string.genre_weak_hint, why.where.quote), why.where.line, onCite, GENRE_WEAK_TAG)
        else -> WeakLine(why, genre)
    }
}

// The line of [WeakEvidenceNote] for a weak [genre] whose reason [why] cites no line.
@Composable
private fun WeakLine(
    why: WeakEvidence,
    genre: GenreLabel,
) {
    val line =
        when (why) {
            is WeakEvidence.FewVotes ->
                stringResource(
                    if (why.musicBrainz) R.string.genre_weak_musicbrainz else R.string.genre_weak_lastfm,
                    ALBUM_MARK,
                    othersClause(why)?.let { stringResource(it) }.orEmpty(),
                )
            is WeakEvidence.Vocalist -> stringResource(R.string.genre_weak_vocalist, why.name, ALBUM_MARK)
            is WeakEvidence.Lyricist -> stringResource(R.string.genre_weak_lyricist, why.name, ALBUM_MARK)
            is WeakEvidence.Language ->
                why.name?.let { stringResource(R.string.genre_weak_language, it, ALBUM_MARK) }
                    ?: stringResource(R.string.genre_weak_lyrics, ALBUM_MARK)
            is WeakEvidence.Featured -> stringResource(R.string.genre_weak_featured, why.name)
            // [WeakEvidenceNote]'s, with its citation.
            is WeakEvidence.ArtistHint -> return
            WeakEvidence.LibraryMark -> stringResource(R.string.genre_weak_library, ALBUM_MARK)
        }
    Text(
        withAlbum(line, genre.label),
        modifier = Modifier.testTag(GENRE_WEAK_TAG),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The clause naming the levels whose tags lack the genre: the artist's (or, with several, the
// artists'), the album's, or both; null with no other level.
private fun othersClause(why: WeakEvidence.FewVotes): Int? {
    val several = why.artists > 1
    return when {
        why.artists > 0 && why.album -> if (several) R.string.genre_weak_both_artists else R.string.genre_weak_both
        why.artists > 0 -> if (several) R.string.genre_weak_artists else R.string.genre_weak_artist
        why.album -> R.string.genre_weak_album
        else -> null
    }
}

/** The test tag of a weak genre's line in its body (#875). */
const val GENRE_WEAK_TAG: String = "genre-panel:weak"
