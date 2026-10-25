package app.sleevesight.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.LinkLook
import app.sleevesight.app.ui.theme.TextPart

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

/**
 * [url], shown without its scheme, as a link of its own ([LinkLabel]) that opens the page in the
 * browser: the whole text the link, with at least the minimum touch target, and an action a screen
 * reader names (`link_open`).
 */
@Composable
internal fun WebLink(
    url: String,
    modifier: Modifier = Modifier,
) {
    LinkLabel(
        TextPart.Link(shownAddress(url), url),
        onClickLabel = stringResource(R.string.link_open),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

/**
 * A link that opens another app or a web page ([onClick]): [text] drawn as every link is
 * ([LinkLook], #442) and the Material *open in new* symbol after it, in the text colour, with at
 * least the minimum touch target. A screen reader reads it as [description] ("Open Spotify"), a
 * button, not the symbol.
 */
@Composable
internal fun ExternalLink(
    text: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        onClick()
                        true
                    }
                },
    ) {
        LinkLook(text, style = MaterialTheme.typography.bodyMedium)
        Icon(
            painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = LocalContentColor.current,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** [PRIVACY_POLICY_URL] as a [WebLink]. */
@Composable
internal fun PrivacyPolicyLink(modifier: Modifier = Modifier) = WebLink(PRIVACY_POLICY_URL, modifier)
