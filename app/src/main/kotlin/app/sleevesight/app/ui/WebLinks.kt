package app.sleevesight.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import app.sleevesight.core.text.WebLinks

// The app's one way to open a link (#1040): every link it shows, a source's or its own, opens here.
// `WebLinkOpenersTest` holds the rest of the app to that: no other `openUri`, `ACTION_VIEW`, or web link
// annotation without a listener of its own.

/**
 * Opens [url] in the browser with [uris], when it is a web address ([WebLinks.of]: `http` or
 * `https`, with a host); says whether it did. Any other scheme (`intent:`, `javascript:`, `file:` …)
 * opens nothing, so a link from a source never opens another app; and where no app opens web pages,
 * nothing happens either: the address is on the screen, to be typed in elsewhere.
 */
internal fun openWebLink(
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

/**
 * Opens [url], a web address ([WebLinks.of]), in the browser in a task of its own
 * (`FLAG_ACTIVITY_NEW_TASK`), for a sign-in whose answer comes back to the app's own activity
 * (#1157): never in the app's task, where the answer would open a second activity above the
 * browser. Says whether it did; nothing opens with no browser, or another scheme.
 */
internal fun openInOwnTask(
    context: Context,
    url: String?,
): Boolean {
    val address = WebLinks.of(url) ?: return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
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
