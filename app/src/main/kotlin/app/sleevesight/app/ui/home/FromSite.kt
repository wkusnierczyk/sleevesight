package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts

// The page a value *Ask AI* found on the web came from (#801).

/**
 * "From {site}" under a value *Ask AI* found on the web (#801), on a line of its own in the credit's
 * style: the page's site ([siteOf]: its host, without `www.`) a link to the page ([url]).
 */
@Composable
internal fun FromSite(url: String) = FromSource(siteOf(url), url, AI_SEARCH_FROM_TAG)

/**
 * "From {name}" on a line of its own in the credit's style, [name] a link to [url], tagged [tag]: under
 * a value *Ask AI* found on the web ([FromSite]), and under a date a source gives (#1132: "From
 * MusicBrainz" under when the music was composed, its page the work's).
 */
@Composable
internal fun FromSource(
    name: String,
    url: String,
    tag: String,
) {
    LinkedText(
        linkedParts(stringResource(R.string.home_ai_from), TextPart.Link(name, url)),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * "From {site} · {site} · …" under the genres *Ask AI* found on the web (#801, #1204): "From" once,
 * then each page's site ([siteOf]) a link of its own to its page ([urls], in their order, each page
 * once; two pages on one site each keep their own link), " · " between, as *Tag sources* separates
 * its sources. The line wraps as text, between sites: a site's name never breaks ([unbroken]), the
 * dot stays with the site before it, and "From" with the first site (a no-break space). A screen
 * reader reaches each site as a link of its own. Nothing when there are no pages.
 */
@Composable
internal fun FromSites(urls: List<String>) =
    SitesLine(
        stringResource(R.string.home_ai_from),
        urls,
        MaterialTheme.typography.bodySmall,
        Modifier.testTag(AI_SEARCH_FROM_TAG),
    )

/**
 * [template] ("From %1$s", "Read more: %1$s") with each page's site ([siteOf]) in its slot, a link of
 * its own to its page ([urls], in their order, each address once), " · " between, as [FromSites]
 * sets them, in [style] and the muted colour: the line wraps between sites, never inside one, and the
 * words before the slot stay with the first site. Nothing when there are no pages.
 */
@Composable
internal fun SitesLine(
    template: String,
    urls: List<String>,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val pages = urls.distinct()
    if (pages.isEmpty()) return
    val sites =
        pages.flatMapIndexed { i, url ->
            val link = TextPart.Link(unbroken(siteOf(url)), url)
            if (i == 0) listOf(link) else listOf(TextPart.Words(SITE_SEPARATOR), link)
        }
    val slot = TextPart.Words(SITES_SLOT)
    val words = linkedParts(template, slot)
    val parts =
        words.flatMapIndexed { i, part ->
            when {
                part === slot -> sites
                // The space before the sites, a no-break one: "From" never ends a line alone.
                words.getOrNull(i + 1) === slot && part is TextPart.Words && part.text.endsWith(" ") ->
                    listOf(TextPart.Words(part.text.dropLast(1) + NO_BREAK_SPACE))
                else -> listOf(part)
            }
        }
    LinkedText(parts, style = style, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * [site] that a line never breaks inside: a word joiner after each hyphen, the one place a host
 * ("lyric-stories.com") may otherwise break (#1204). A dot between letters or digits is no break.
 */
internal fun unbroken(site: String): String = site.replace("-", "-$WORD_JOINER")

/** Between two sites on the From line: the dot held to the site before it (a no-break space). */
internal const val NO_BREAK_SPACE = "\u00A0"

internal const val SITE_SEPARATOR = "$NO_BREAK_SPACE· "

private const val WORD_JOINER = "\u2060"

private const val SITES_SLOT = "\u0000"

/**
 * The site of the page at [url]: its host ([WebLinks.host], as a browser reads it, so it names the
 * site the link opens), without `www.`, or the address itself when it has none.
 */
internal fun siteOf(url: String): String = hostOf(url) ?: url
