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
internal val PRIVACY_POLICY_SHOWN: String = PRIVACY_POLICY_URL.removePrefix("https://")

/**
 * Opens [PRIVACY_POLICY_URL] in the browser. Where no app opens web pages, nothing happens; the
 * address is shown wherever the policy is offered, so it can be typed in elsewhere.
 */
@Composable
internal fun rememberOpenPrivacyPolicy(): () -> Unit {
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        {
            try {
                uriHandler.openUri(PRIVACY_POLICY_URL)
            } catch (_: IllegalArgumentException) {
                // No app opens web pages: the address is on the screen.
            }
        }
    }
}

/**
 * [PRIVACY_POLICY_SHOWN], styled as the credits' links are, opening the policy when tapped: a
 * clickable text, the whole of it the link, with at least the minimum touch target, and an action
 * a screen reader names (`privacy_policy_open`).
 */
@Composable
internal fun PrivacyPolicyLink(modifier: Modifier = Modifier) {
    val open = rememberOpenPrivacyPolicy()
    Text(
        PRIVACY_POLICY_SHOWN,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier =
            modifier
                .clickable(onClickLabel = stringResource(R.string.privacy_policy_open), onClick = open)
                .minimumInteractiveComponentSize(),
    )
}
