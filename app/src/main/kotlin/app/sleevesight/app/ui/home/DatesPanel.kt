package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.phoneDateForm
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.YearTag
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.text.DateWhen

// The *Dates* panel under the album (#724): each date known about the album, the track and the song,
// said in full.

/**
 * *Dates* in the line under the album ([InPlaceLine]), shown for every track (#724): its button,
 * and its panel, which says each of [sentences] as a sentence of its own; with none, why
 * ([NoDates], [none]): with MusicBrainz off, a link to *Settings → Sources* ([openSources]), and
 * when it could not be reached, *Retry* ([retry], [RetryLine]), which hands the focus to the panel's
 * *Close* as it goes. Under why, but while MusicBrainz is still asked, *Search with AI* ([search],
 * #773), which says what it found as the panel says MusicBrainz's dates ([aiDates]). Open, it
 * asks [look], which looks up the album's release group's first release (#1099) and may make its
 * album sentence an edition's; null when there is none to ask, asked again when it changes.
 */
@Suppress("LongParameterList") // What the panel says, and each of its ways out, from its own place.
@Composable
internal fun datesItem(
    sentences: List<DateSentence>,
    none: NoDates,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
    search: PanelSearch = PanelSearch(),
    look: (() -> Unit)? = null,
): InPlaceItem =
    InPlaceItem(
        id = InPlace.DATES,
        name = stringResource(R.string.home_dates),
        action = stringResource(R.string.home_dates_show),
        tag = DATES_CHIP_TAG,
        panelTag = DATES_PANEL_TAG,
    ) { toClose ->
        LaunchedEffect(look) { look?.invoke() }
        if (sentences.isEmpty()) {
            NoDatesText(none, retry, openSources, toClose)
            if (none != NoDates.LOOKING) {
                AiSearchBlock(search, SearchKind.DATES) { answer, _ ->
                    val header = search.header ?: search.track?.nowPlaying
                    if (answer is SearchAnswer.Dates && header != null) AiDatesPanel(aiDatesCited(answer, header))
                }
            }
        } else {
            DatesPanel(sentences)
        }
    }

/**
 * What *Ask AI* found for *Dates* (#773): each of [sentences] as [DatesPanel] says it, and under one
 * found on the web its page, "From {site}" (#801, [FromSite]).
 */
@Composable
private fun AiDatesPanel(sentences: List<Pair<DateSentence, String?>>) {
    for ((sentence, source) in sentences) {
        Text(dateText(sentence), style = MaterialTheme.typography.bodyLarge)
        source?.let { FromSite(it) }
    }
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
        // Nothing asked for a track the last clear holds (#1112): it says so, without the record.
        NoDates.LOOKING ->
            Busy(
                waitingOrCleared(stringResource(R.string.home_musicbrainz_looking)),
                style,
                record = !LocalTrackCleared.current,
            )
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
        is DateSentence.Album -> {
            val format =
                sentence.kind.pick(
                    R.string.home_dates_album,
                    R.string.home_dates_album_single,
                    R.string.home_dates_album_ep,
                )
            AnnotatedString(stringResource(format, whenText(sentence.date)))
        }
        is DateSentence.Edition -> editionText(sentence)
        is DateSentence.FirstOriginal ->
            when (sentence.kind) {
                ReleaseKind.SINGLE -> AnnotatedString(stringResource(R.string.home_dates_first_original_single))
                ReleaseKind.EP -> AnnotatedString(stringResource(R.string.home_dates_first_original_ep))
                null -> withAlbum(stringResource(R.string.home_dates_first_original, ALBUM_MARK), sentence.title)
            }
        is DateSentence.FirstHere -> {
            val format =
                sentence.kind.pick(
                    R.string.home_dates_first_here,
                    R.string.home_dates_first_here_single,
                    R.string.home_dates_first_here_ep,
                )
            AnnotatedString(stringResource(format))
        }
        is DateSentence.First -> {
            val date = whenText(sentence.date)
            val title = sentence.title
            if (title == null) {
                AnnotatedString(stringResource(R.string.home_dates_first_when, date))
            } else {
                val format =
                    sentence.kind.pick(
                        if (sentence.album) R.string.home_dates_first_album else R.string.home_dates_first,
                        R.string.home_dates_first_single,
                        R.string.home_dates_first_ep,
                    )
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
                    sentence.kind.pick(
                        R.string.home_dates_original,
                        R.string.home_dates_original_single,
                        R.string.home_dates_original_ep,
                    )
                withAlbum(stringResource(format, date, sentence.artist, ALBUM_MARK), title)
            }
        }
    }

// An edition's sentences (#1099, #1116), as one paragraph: its album's first release, its title in
// italics, then the edition as [DateSentence.Edition.edition] says it, a title it names in italics too.
@Composable
private fun editionText(sentence: DateSentence.Edition): AnnotatedString {
    val opener =
        sentence.kind.pick(
            R.string.home_dates_edition_first_album,
            R.string.home_dates_edition_first_single,
            R.string.home_dates_edition_first_ep,
        )
    val original = withAlbum(stringResource(opener, ALBUM_MARK, whenText(sentence.firstDate)), sentence.title)
    val edition =
        when (val part = sentence.edition) {
            is EditionPart.Same -> AnnotatedString(stringResource(R.string.home_dates_edition, whenText(part.date)))
            is EditionPart.Named ->
                withAlbum(stringResource(R.string.home_dates_edition_named, ALBUM_MARK, whenText(part.date)), part.name)
            is EditionPart.Remaster -> {
                val tag = tagText(part.tag)
                val line = stringResource(R.string.home_dates_edition_remaster, tag, ALBUM_MARK, whenText(part.date))
                withAlbum(line, part.name)
            }
            is EditionPart.Its -> AnnotatedString(stringResource(R.string.home_dates_edition_its, tagText(part.tag)))
        }
    return original + AnnotatedString(" ") + edition
}

// A remaster or an edition as the title names it, by its year (#1116): "2024 remaster".
@Composable
private fun tagText(tag: YearTag): String =
    stringResource(if (tag.remaster) R.string.home_dates_remaster_year else R.string.home_dates_edition_year, tag.year)

// Of a sentence's three wordings, the one this kind of release takes: [album]'s for an album or a
// release of no type named, [single]'s, [ep]'s.
private fun ReleaseKind?.pick(
    album: Int,
    single: Int,
    ep: Int,
): Int =
    when (this) {
        ReleaseKind.SINGLE -> single
        ReleaseKind.EP -> ep
        null -> album
    }

/**
 * [date] as precisely as it is known ([DateWhen]): "on 29 July 2014" (the day without a leading
 * zero), "in July 2014", "in 2014"; as given when it has no year, which [homeDates] never gives. The
 * date in English in the phone's form ([phoneDateForm], #957: "on July 29, 2014" on a phone set to US
 * English), as the prompts ask the AI to write it, read again when the phone's language or region
 * changes; the words around it from the resources.
 */
@Composable
internal fun whenText(date: String): String {
    val known = DateWhen.of(date) ?: return date
    val phone = LocalConfiguration.current.locales[0]
    val words = remember(phone) { phoneDateForm(phone) }.format(known)
    return when (known) {
        is DateWhen.Day -> stringResource(R.string.home_dates_on_day, words)
        is DateWhen.Month -> stringResource(R.string.home_dates_in_month, words)
        is DateWhen.Year -> stringResource(R.string.home_dates_in_year, words)
    }
}

/** The test tags of *Dates*' button and its panel (#724), the panel's heading and *Close* as [Panel] makes them. */
const val DATES_CHIP_TAG: String = "dates-chip"
const val DATES_PANEL_TAG: String = "dates-panel"
const val DATES_PANEL_HEADING_TAG: String = "dates-panel:heading"
const val DATES_PANEL_CLOSE_TAG: String = "dates-panel:close"
