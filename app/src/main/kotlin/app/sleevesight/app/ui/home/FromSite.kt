package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
internal fun FromSite(url: String) {
    val site = siteOf(url)
    LinkedText(
        linkedParts(stringResource(R.string.home_ai_from), TextPart.Link(site, url)),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(AI_SEARCH_FROM_TAG),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The site of the page at [url]: its host, without `www.`, or the address itself when it has none. */
internal fun siteOf(url: String): String =
    url
        .substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase()
        .removePrefix("www.")
        .ifEmpty { url }
