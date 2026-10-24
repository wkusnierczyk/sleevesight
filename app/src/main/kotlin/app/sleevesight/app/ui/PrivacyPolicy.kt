package app.sleevesight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import app.sleevesight.app.R

/**
 * The privacy policy on the site (`privacy.md`, #57), with where the app stands under the GDPR and
 * the EU AI Act (#295). The first-run screen and the settings link to it.
 */
internal const val PRIVACY_POLICY_URL = "https://sleevesight.app/privacy.html"

/** [PRIVACY_POLICY_URL] as it is shown: the address without its scheme. */
internal val PRIVACY_POLICY_SHOWN: String = shownAddress(PRIVACY_POLICY_URL)

/** The web address [url] as a link shows it: without its scheme. */
internal fun shownAddress(url: String): String = url.removePrefix("https://")

/**
 * Opens [url] in the browser. Where no app opens web pages, nothing happens; the address is shown
 * wherever a link is offered, so it can be typed in elsewhere.
 */
@Composable
internal fun rememberOpenUrl(url: String): () -> Unit {
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

/** Opens [PRIVACY_POLICY_URL] in the browser, as [rememberOpenUrl] does. */
@Composable
internal fun rememberOpenPrivacyPolicy(): () -> Unit = rememberOpenUrl(PRIVACY_POLICY_URL)

/**
 * [url], shown without its scheme and styled as the credits' links are, opening the page when
 * tapped: a clickable text, the whole of it the link, with at least the minimum touch target, and
 * an action a screen reader names (`link_open`).
 */
@Composable
internal fun WebLink(
    url: String,
    modifier: Modifier = Modifier,
) {
    val open = rememberOpenUrl(url)
    Text(
        shownAddress(url),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier =
            modifier
                .clickable(onClickLabel = stringResource(R.string.link_open), onClick = open)
                .minimumInteractiveComponentSize(),
    )
}

/** [PRIVACY_POLICY_URL] as a [WebLink]. */
@Composable
internal fun PrivacyPolicyLink(modifier: Modifier = Modifier) = WebLink(PRIVACY_POLICY_URL, modifier)
