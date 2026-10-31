package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.model.SourceId

// The *Dates* panel under the album (#724): each date known about the album, the track and the song,
// said in full.

/**
 * *Dates* in the line under the album ([InPlaceLine]), shown for every track (#724): its button,
 * and its panel, which says each of [sentences] as a sentence of its own; with none, why
 * ([NoDates], [none]): with MusicBrainz off, a link to *Settings → Sources* ([openSources]), and
 * when it could not be reached, *Retry* ([retry], [RetryLine]), which hands the focus to the panel's
 * *Close* as it goes.
 */
@Composable
internal fun datesItem(
    sentences: List<DateSentence>,
    none: NoDates,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
): InPlaceItem =
    InPlaceItem(
        id = InPlace.DATES,
        name = stringResource(R.string.home_dates),
        action = stringResource(R.string.home_dates_show),
        tag = DATES_CHIP_TAG,
        panelTag = DATES_PANEL_TAG,
    ) { toClose ->
        if (sentences.isEmpty()) NoDatesText(none, retry, openSources, toClose) else DatesPanel(sentences)
    }

/** The panel's content: each of [sentences] on its own, a release's title in italics. */
@Composable
internal fun DatesPanel(sentences: List<DateSentence>) {
    for (sentence in sentences) {
        Text(dateText(sentence), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Why the panel has no date ([none]), in place of the sentences: MusicBrainz off on two lines, the
 * second a link to *Settings → Sources* ([openSources]); unreachable with *Retry* ([retry]) beside it,
 * whose tap then runs [afterRetry] (the focus handed to the panel's *Close*, #724), since the line
 * gives way to "Looking on MusicBrainz…".
 */
@Composable
internal fun NoDatesText(
    none: NoDates,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
    afterRetry: () -> Unit = {},
) {
    val style = MaterialTheme.typography.bodyLarge
    when (none) {
        NoDates.LOOKING -> Text(stringResource(R.string.home_musicbrainz_looking), style = style)
        NoDates.OFF -> {
            Text(stringResource(R.string.home_musicbrainz_off), style = style)
            val link =
                TextPart.Action(
                    stringResource(R.string.home_settings_sources_link),
                    SettingsTarget.sources.linkTag,
                    openSources,
                )
            LinkedText(linkedParts(stringResource(R.string.home_musicbrainz_off_see), link), style = style)
        }
        NoDates.UNREACHABLE ->
            RetryLine(
                stringResource(R.string.home_musicbrainz_unreachable),
                sourceName(SourceId.MUSICBRAINZ),
                retry,
                afterRetry = afterRetry,
            )
        NoDates.NOT_FOUND -> Text(stringResource(R.string.home_musicbrainz_not_found), style = style)
        NoDates.NO_DATES -> Text(stringResource(R.string.home_dates_none), style = style)
    }
}

/** [sentence] as the panel says it ([DateSentence]), the title of a release in italics. */
@Composable
internal fun dateText(sentence: DateSentence): AnnotatedString =
    when (sentence) {
        is DateSentence.Album -> AnnotatedString(stringResource(R.string.home_dates_album, whenText(sentence.date)))
        DateSentence.FirstHere -> AnnotatedString(stringResource(R.string.home_dates_first_here))
        is DateSentence.First -> {
            val date = whenText(sentence.date)
            val title = sentence.title
            if (title == null) {
                AnnotatedString(stringResource(R.string.home_dates_first_when, date))
            } else {
                val format =
                    when (sentence.kind) {
                        ReleaseKind.SINGLE -> R.string.home_dates_first_single
                        ReleaseKind.EP -> R.string.home_dates_first_ep
                        null -> R.string.home_dates_first
                    }
                withAlbum(stringResource(format, ALBUM_MARK, date), title)
            }
        }
        is DateSentence.Original -> {
            val date = whenText(sentence.date)
            val title = sentence.title
            if (title == null) {
                AnnotatedString(stringResource(R.string.home_dates_original_unreleased, date, sentence.artist))
            } else {
                val format =
                    when (sentence.kind) {
                        ReleaseKind.SINGLE -> R.string.home_dates_original_single
                        ReleaseKind.EP -> R.string.home_dates_original_ep
                        null -> R.string.home_dates_original
                    }
                withAlbum(stringResource(format, date, sentence.artist, ALBUM_MARK), title)
            }
        }
    }

/**
 * [date] as precisely as it is known ([DateWhen]): "on 29 July 2014" (the day without a leading
 * zero), "in July 2014", "in 2014"; as given when it has no year, which [homeDates] never gives.
 */
@Composable
internal fun whenText(date: String): String {
    val months = stringArrayResource(R.array.home_dates_months)
    return when (val known = DateWhen.of(date)) {
        is DateWhen.Day ->
            stringResource(R.string.home_dates_on_day, known.day.toString(), months[known.month - 1], known.year)
        is DateWhen.Month -> stringResource(R.string.home_dates_in_month, months[known.month - 1], known.year)
        is DateWhen.Year -> stringResource(R.string.home_dates_in_year, known.year)
        null -> date
    }
}

/** The test tags of *Dates*' button and its panel (#724), the panel's heading and *Close* as [Panel] makes them. */
const val DATES_CHIP_TAG: String = "dates-chip"
const val DATES_PANEL_TAG: String = "dates-panel"
const val DATES_PANEL_HEADING_TAG: String = "dates-panel:heading"
const val DATES_PANEL_CLOSE_TAG: String = "dates-panel:close"
