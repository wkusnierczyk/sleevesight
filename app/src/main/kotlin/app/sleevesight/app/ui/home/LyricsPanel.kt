package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.LyricsLinks
import app.sleevesight.core.lyrics.NoLinkReason
import app.sleevesight.core.lyrics.lyricsLinks
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder

// The *Lyrics* panel under the album (#728): links to the song's lyrics on the sites MusicBrainz
// links to, or a search on Genius; Sleevesight never fetches or shows lyrics.

/**
 * *Lyrics* in the line under the album ([InPlaceLine]), shown for every track while *Lyrics links* is
 * on (#728): its button, and its panel, which holds what [links] says ([LyricsLinks]): each page,
 * one per site, or why there is none, then the Genius search, or that it is off. MusicBrainz off
 * links to *Settings → Sources* ([openSources]), the search off to *Settings → Appearance*
 * ([openAppearance]); MusicBrainz not reached shows *Retry* ([retry], [RetryLine]), which hands the
 * focus to the panel's *Close* as it goes.
 */
@Composable
internal fun lyricsItem(
    links: LyricsLinks,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
    openAppearance: () -> Unit,
): InPlaceItem =
    InPlaceItem(
        id = InPlace.LYRICS,
        name = stringResource(R.string.home_lyrics),
        action = stringResource(R.string.home_lyrics_show),
        tag = LYRICS_CHIP_TAG,
        panelTag = LYRICS_PANEL_TAG,
    ) { toClose -> LyricsPanel(links, retry, openSources, openAppearance, toClose) }

/**
 * What the *Lyrics* panel holds for [track] ([lyricsLinks], #728), MusicBrainz's state read from it;
 * [search] is *Search Genius when there is no link*. Looking until the pipeline reaches the track.
 */
internal fun homeLyrics(
    track: TrackState?,
    search: Boolean,
): LyricsLinks = track?.let { lyricsLinks(it, search, JvmAccentFolder) } ?: LyricsLinks.Looking

/**
 * The panel's content ([lyricsItem]): with pages, each ([PageLink]); else why there is none
 * ([NoLinkText]), then the search, or, while it is off, a line saying so with a link to where it is
 * switched on; a track MusicBrainz lists as instrumental has neither. Under any link, that it opens
 * in the browser.
 */
@Composable
internal fun LyricsPanel(
    links: LyricsLinks,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
    openAppearance: () -> Unit,
    afterRetry: () -> Unit = {},
) {
    val style = MaterialTheme.typography.bodyLarge
    when (links) {
        LyricsLinks.Looking -> Text(stringResource(R.string.home_musicbrainz_looking), style = style)
        LyricsLinks.Instrumental -> Text(stringResource(R.string.home_lyrics_instrumental), style = style)
        is LyricsLinks.Links -> {
            for (link in links.links) PageLink(link, search = false)
            Browser()
        }
        is LyricsLinks.NoLink -> {
            NoLinkText(links.reason, retry, openSources, afterRetry)
            val search = links.search
            if (search == null) {
                SearchOff(openAppearance)
            } else {
                PageLink(search, search = true)
                Browser()
            }
        }
    }
}

/**
 * Why there is no page to link ([reason]), as *Dates* says it for MusicBrainz ([NoDatesText]):
 * MusicBrainz off on two lines, the second a link to *Settings → Sources* ([openSources]);
 * unreachable, or a lookup failed, with *Retry* ([retry]) beside it, whose tap then runs
 * [afterRetry].
 */
@Composable
private fun NoLinkText(
    reason: NoLinkReason,
    retry: (() -> Unit)?,
    openSources: () -> Unit,
    afterRetry: () -> Unit,
) {
    val style = MaterialTheme.typography.bodyLarge
    when (reason) {
        NoLinkReason.MusicBrainzOff -> NoDatesText(NoDates.OFF, retry = null, openSources = openSources)
        NoLinkReason.Unreachable ->
            RetryLine(
                stringResource(R.string.home_musicbrainz_unreachable),
                sourceName(SourceId.MUSICBRAINZ),
                retry,
                afterRetry = afterRetry,
            )
        NoLinkReason.NotFound -> Text(stringResource(R.string.home_musicbrainz_not_found), style = style)
        NoLinkReason.NoLyricsPage -> Text(stringResource(R.string.home_lyrics_none), style = style)
    }
}

/**
 * [link] as a link of its own ([LinkLabel]), with the mark of a page that opens outside the app,
 * and under it where it goes: the song's page, or, for a [search], a search that may find another
 * song of the same name.
 */
@Composable
private fun PageLink(
    link: LyricsLink,
    search: Boolean,
) {
    val site = link.site.siteName
    val text = stringResource(if (search) R.string.home_lyrics_search else R.string.home_lyrics_page, site)
    val note = stringResource(if (search) R.string.home_lyrics_search_note else R.string.home_lyrics_page_note)
    Column(Modifier.testTag(if (search) LYRICS_SEARCH_TAG else lyricsPageTag(link.site.name))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkLabel(
                TextPart.Link(text, link.url),
                onClickLabel = stringResource(R.string.link_open),
                style = MaterialTheme.typography.bodyLarge,
            )
            Icon(
                painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Under the links: that a tap opens the browser. */
@Composable
private fun Browser() {
    Text(
        stringResource(R.string.home_lyrics_browser),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The search off, on two lines, the second a link to *Settings → Appearance* ([openAppearance]). */
@Composable
private fun SearchOff(openAppearance: () -> Unit) {
    val style = MaterialTheme.typography.bodyLarge
    Text(stringResource(R.string.home_lyrics_search_off), style = style)
    val link =
        TextPart.Action(
            stringResource(R.string.home_settings_appearance_link),
            SettingsTarget.lyricsSearch.linkTag,
            openAppearance,
        )
    LinkedText(linkedParts(stringResource(R.string.home_lyrics_search_off_see), link), style = style)
}

/** The test tags of *Lyrics*' button and its panel (#728), the panel's heading and *Close* as [Panel] makes them. */
const val LYRICS_CHIP_TAG: String = "lyrics-chip"
const val LYRICS_PANEL_TAG: String = "lyrics-panel"
const val LYRICS_PANEL_HEADING_TAG: String = "lyrics-panel:heading"
const val LYRICS_PANEL_CLOSE_TAG: String = "lyrics-panel:close"

/** The test tag of the Genius search's link in the panel. */
const val LYRICS_SEARCH_TAG: String = "lyrics-search"

/** The test tag of the link to the page on the site [site] names ([app.sleevesight.core.lyrics.LyricsSite]'s name). */
fun lyricsPageTag(site: String): String = "lyrics-page:$site"
