package app.sleevesight.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.app.ui.DOCS_URL
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.LICENCE_URL
import app.sleevesight.app.ui.PRIVACY_POLICY_URL
import app.sleevesight.app.ui.SOURCE_CODE_URL
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.shownAddress
import app.sleevesight.core.settings.Settings

/** The tag of *About*'s *Version* row, for tests. */
internal const val VERSION_TAG = "settings_version"

/** The taps on *Version* that turn developer mode on, as Android's build number takes. */
internal const val DEVELOPER_TAPS = 7

/** From this tap on *Version*, a message counts down the taps left. */
private const val COUNTDOWN_FROM = 4

/**
 * *About*: *Version*, whose seven taps turn developer mode on ([VersionRow]); the way to the
 * credits; and the site's pages, each a row with its address, to type in where no browser opens,
 * and *Open website ↗*: the documentation's index (#421), through which the usage and settings
 * pages (#415, #416) and later ones are reached, the privacy policy, with where the app stands under
 * the GDPR and the EU AI Act (#295), the source code and its licence.
 */
@Composable
internal fun AboutSection(
    settings: Settings,
    update: Update,
    openCredits: () -> Unit,
) {
    VersionRow(settings.developerMode) { update { it.copy(developerMode = true) } }
    ValueRow(
        title = stringResource(R.string.credits_title),
        value = stringResource(R.string.settings_credits_value),
        onClick = openCredits,
    )
    LinkRow(R.string.settings_docs, DOCS_URL, R.string.settings_docs_summary)
    LinkRow(R.string.settings_privacy, PRIVACY_POLICY_URL, R.string.settings_privacy_summary)
    LinkRow(R.string.settings_source_code, SOURCE_CODE_URL, R.string.settings_source_code_summary)
    LinkRow(R.string.settings_licence, LICENCE_URL, R.string.settings_licence_summary)
}

/**
 * *Version*: the app's version and build, `0.1.3 (1003)`. Seven taps turn developer mode on
 * ([onDeveloperMode]), as Android's build number does: from the fourth, a short message counts down
 * the taps left, the seventh says developer mode is on, and a tap once it is on says so. A screen
 * reader reads the row as the version, with nothing of the taps; it hears the messages as they show.
 */
@Composable
internal fun VersionRow(
    developerMode: Boolean,
    onDeveloperMode: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var taps by remember { mutableIntStateOf(0) }
    // Developer mode as this row turned it on, before the stored settings show it.
    var turnedOn by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Toast?>(null) }
    val say = { message: String ->
        // One message at a time, the latest, as a quick run of taps would otherwise queue them.
        shown?.cancel()
        shown = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }
    Column(
        modifier =
            Modifier
                .testTag(VERSION_TAG)
                .fillMaxWidth()
                .clickable {
                    if (developerMode || turnedOn) {
                        say(resources.getString(R.string.settings_developer_already_on))
                        return@clickable
                    }
                    taps++
                    val left = DEVELOPER_TAPS - taps
                    when {
                        left <= 0 -> {
                            turnedOn = true
                            onDeveloperMode()
                            say(resources.getString(R.string.settings_developer_on))
                        }

                        taps >= COUNTDOWN_FROM -> {
                            say(resources.getQuantityString(R.plurals.settings_developer_taps_left, left, left))
                        }
                    }
                }.padding(vertical = 8.dp),
    ) {
        Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A page on the web: its [title], what it is ([summary]), its address without the scheme, to type
 * in where no browser opens, and *Open website ↗*, which opens it in the browser.
 */
@Composable
private fun LinkRow(
    title: Int,
    url: String,
    summary: Int,
) {
    val name = stringResource(title)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        Text(
            shownAddress(url),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExternalLink(
            text = stringResource(R.string.settings_open_website),
            description = stringResource(R.string.settings_open_page, name),
            onClick = rememberOpenUrl(url),
        )
    }
}
