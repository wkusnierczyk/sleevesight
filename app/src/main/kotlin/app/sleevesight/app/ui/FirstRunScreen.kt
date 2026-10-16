package app.sleevesight.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.SleevesightTheme

/**
 * What Sleevesight reads, what leaves the phone and to whom (design, *Privacy and security*), and
 * the way to notification access. [openAccessSettings] returns false when no settings screen for
 * it could be opened.
 */
@Composable
fun FirstRunScreen(
    accessGranted: Boolean,
    openAccessSettings: () -> Boolean,
    onContinue: () -> Unit,
) {
    var noSettingsScreen by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.first_run_intro), style = MaterialTheme.typography.bodyLarge)

            Heading(R.string.first_run_reads_title)
            Body(R.string.first_run_reads)

            Heading(R.string.first_run_leaves_title)
            Bullet(R.string.first_run_to_sources)
            Bullet(R.string.first_run_to_ai)
            Bullet(R.string.first_run_to_spotify)
            Bullet(R.string.first_run_to_art_host)
            Body(R.string.first_run_ip_address)

            Heading(R.string.first_run_stays_title)
            Body(R.string.first_run_stays)
            Body(R.string.first_run_no_tracking)

            if (accessGranted) {
                Heading(R.string.access_on)
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.first_run_continue))
                }
            } else {
                Heading(R.string.access_off)
                Body(R.string.access_off_hint)
                Button(
                    onClick = { noSettingsScreen = !openAccessSettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.open_access_settings))
                }
                if (noSettingsScreen) {
                    Text(
                        stringResource(R.string.no_access_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Body(
    @StringRes text: Int,
) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(
    @StringRes text: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Body(text)
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun FirstRunAccessOffPreview() {
    SleevesightTheme { FirstRunScreen(accessGranted = false, openAccessSettings = { true }, onContinue = {}) }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun FirstRunAccessOnPreview() {
    SleevesightTheme { FirstRunScreen(accessGranted = true, openAccessSettings = { true }, onContinue = {}) }
}
