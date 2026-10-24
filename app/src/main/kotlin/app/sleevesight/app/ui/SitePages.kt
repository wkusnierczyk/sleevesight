package app.sleevesight.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler

/** The usage page on the site (`docs/tutorials/usage/index.md`, #415), linked from *About*. */
internal const val USAGE_PAGE_URL = "https://sleevesight.app/docs/tutorials/usage/"

/** The settings page on the site (`docs/tutorials/settings/index.md`, #416), linked from *About*. */
internal const val SETTINGS_PAGE_URL = "https://sleevesight.app/docs/tutorials/settings/"

/** A page of the site as it is shown: the address without its scheme. */
internal fun shownAddress(url: String): String = url.removePrefix("https://")

/**
 * Opens [url], a page of the site, in the browser. Where no app opens web pages, nothing happens;
 * the address is shown wherever the page is offered, so it can be typed in elsewhere.
 */
@Composable
internal fun rememberOpenSitePage(url: String): () -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler, url) {
        {
            try {
                uriHandler.openUri(url)
            } catch (_: IllegalArgumentException) {
                // No app opens web pages: the address is on the screen.
            }
        }
    }
}
