package io.github.wkusnierczyk.sleevesight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.wkusnierczyk.sleevesight.app.R
import io.github.wkusnierczyk.sleevesight.app.ui.theme.SleevesightTheme

/**
 * Where the first-run flow lands. A placeholder until the now-playing screen: it says whether
 * notification access is on, and leads back to the first-run screen.
 */
@Composable
fun HomeScreen(
    accessGranted: Boolean,
    onOpenFirstRun: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            if (accessGranted) {
                Text(stringResource(R.string.home_placeholder), style = MaterialTheme.typography.bodyLarge)
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
    SleevesightTheme { HomeScreen(accessGranted = true, onOpenFirstRun = {}) }
}

@Preview(showBackground = true)
@Composable
private fun HomeAccessOffPreview() {
    SleevesightTheme { HomeScreen(accessGranted = false, onOpenFirstRun = {}) }
}
