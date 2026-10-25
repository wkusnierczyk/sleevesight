package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.PRIVACY_POLICY_SHOWN
import app.sleevesight.app.ui.SETTINGS_PAGE_URL
import app.sleevesight.app.ui.USAGE_PAGE_URL
import app.sleevesight.app.ui.rememberOpenPrivacyPolicy
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.shownAddress

/**
 * The way to the credits; the usage and settings pages on the site (#415, #416); the first-run
 * screen's explanation of what leaves the device (#441), as Home's bottom link opens it; and the
 * privacy policy on the site, with where the app stands under the GDPR and the EU AI Act (#295).
 * The pages open in the browser, and each row shows its address, so it can be typed in where no
 * browser opens ([rememberOpenUrl]).
 */
@Composable
internal fun AboutSection(
    openCredits: () -> Unit,
    openWhatLeaves: () -> Unit,
) {
    val openPrivacyPolicy = rememberOpenPrivacyPolicy()
    SectionHeading(R.string.settings_section_about)
    ValueRow(
        title = stringResource(R.string.credits_title),
        value = stringResource(R.string.settings_credits_value),
        onClick = openCredits,
    )
    ValueRow(
        title = stringResource(R.string.settings_usage_page),
        value = shownAddress(USAGE_PAGE_URL),
        summary = stringResource(R.string.settings_usage_page_summary),
        onClick = rememberOpenUrl(USAGE_PAGE_URL),
    )
    ValueRow(
        title = stringResource(R.string.settings_settings_page),
        value = shownAddress(SETTINGS_PAGE_URL),
        summary = stringResource(R.string.settings_settings_page_summary),
        onClick = rememberOpenUrl(SETTINGS_PAGE_URL),
    )
    ValueRow(
        title = stringResource(R.string.settings_what_leaves),
        value = stringResource(R.string.settings_what_leaves_value),
        onClick = openWhatLeaves,
    )
    ValueRow(
        title = stringResource(R.string.settings_privacy),
        value = PRIVACY_POLICY_SHOWN,
        summary = stringResource(R.string.settings_privacy_summary),
        onClick = openPrivacyPolicy,
    )
}
