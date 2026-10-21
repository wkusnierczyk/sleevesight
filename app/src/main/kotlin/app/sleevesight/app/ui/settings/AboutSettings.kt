package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.PRIVACY_POLICY_SHOWN
import app.sleevesight.app.ui.rememberOpenPrivacyPolicy

/**
 * The way to the credits, and the privacy policy on the site, with where the app stands under the
 * GDPR and the EU AI Act (#295), opened in the browser. Its row shows the address, so it can be
 * typed in where no browser opens ([rememberOpenPrivacyPolicy]).
 */
@Composable
internal fun AboutSection(openCredits: () -> Unit) {
    val openPrivacyPolicy = rememberOpenPrivacyPolicy()
    SectionHeading(R.string.settings_section_about)
    ValueRow(
        title = stringResource(R.string.credits_title),
        value = stringResource(R.string.settings_credits_value),
        onClick = openCredits,
    )
    ValueRow(
        title = stringResource(R.string.settings_privacy),
        value = PRIVACY_POLICY_SHOWN,
        summary = stringResource(R.string.settings_privacy_summary),
        onClick = openPrivacyPolicy,
    )
}
