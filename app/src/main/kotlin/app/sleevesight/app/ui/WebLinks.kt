package app.sleevesight.app.ui

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import app.sleevesight.core.text.WebLinks

// The app's one way to open a link (#1040): every link it shows, a source's or its own, opens here.
// `WebLinkOpenersTest` holds the rest of the app to that.

/**
 * Opens [url] in the browser with [uris], when it is a web address ([WebLinks.of]: `http` or
 * `https`, with a host); says whether it did. Any other scheme (`intent:`, `javascript:`, `file:` …)
 * opens nothing, so a link from a source never opens another app; and where no app opens web pages,
 * nothing happens either: the address is on the screen, to be typed in elsewhere.
 */
fun openWebLink(
    uris: UriHandler,
    url: String?,
): Boolean {
    val address = WebLinks.of(url) ?: return false
    return try {
        uris.openUri(address)
        true
    } catch (_: IllegalArgumentException) {
        // Compose's handler reports no app that opens web pages this way.
        false
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** What a tap on a link to [url] does: [openWebLink] with the composition's [LocalUriHandler]. */
@Composable
internal fun rememberOpenUrl(url: String): () -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler, url) { { openWebLink(uriHandler, url) } }
}
