package app.sleevesight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.theme.SleevesightTheme

/**
 * Where the first-run flow lands: the app's logo ([HeaderLogo]) and the way to the settings,
 * then, while notification access is on, what is playing ([pipeline], the now-playing content),
 * and the way back to what leaves the phone; while it is off, the way to turn it on. The whole
 * screen scrolls, so a citation can bring its evidence line into view.
 */
@Composable
fun HomeScreen(
    accessGranted: Boolean,
    onOpenFirstRun: () -> Unit,
    onOpenSettings: () -> Unit,
    pipeline: @Composable () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The logo in place of the app's name (#383), named as the name was.
                val name = stringResource(R.string.app_name)
                HeaderLogo(Modifier.semantics { contentDescription = name })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_settings)) }
            }
            if (accessGranted) {
                pipeline()
                TextButton(onClick = onOpenFirstRun) { Text(stringResource(R.string.home_what_leaves)) }
            } else {
                Text(stringResource(R.string.home_access_off), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onOpenFirstRun) { Text(stringResource(R.string.home_turn_on_access)) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeAccessOnPreview() {
    SleevesightTheme {
        HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) {
            Text(stringResource(R.string.home_placeholder))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeAccessOffPreview() {
    SleevesightTheme { HomeScreen(accessGranted = false, onOpenFirstRun = {}, onOpenSettings = {}) }
}
