package app.sleevesight.app.ui.home

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R

// After *Clear history* under *Look up the track again*'s *On request* (#1112): the track on Home
// keeps its header, but what was found for it went with the history and nothing is asked for it,
// so each field says so in place of its waiting text, and *Key insights* offers *Look up again*.

/**
 * Whether the track on Home is the one the last *Clear history* holds (#1112, `PipelineView.cleared`):
 * every field that would wait for its run ("Gathering evidence…", "Looking…") says
 * [R.string.home_cleared] instead, since no run is under way. False elsewhere.
 */
internal val LocalTrackCleared = staticCompositionLocalOf { false }

/**
 * *Key insights* for a track the last clear holds (#1112): that its findings were cleared, and *Look
 * up again* under it, a new run as a new track's ([onLookUpAgain]).
 */
@Composable
internal fun ClearedInsight(onLookUpAgain: () -> Unit) {
    Text(
        stringResource(R.string.home_insight_cleared),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onLookUpAgain, modifier = Modifier.testTag(LOOK_UP_AGAIN_TAG)) {
        Text(stringResource(R.string.home_look_up_again))
    }
}

/** A field's waiting text ([looking]), or, for a track the last clear holds, that it was cleared (#1112). */
@Composable
internal fun waitingOrCleared(looking: String): String =
    if (LocalTrackCleared.current) stringResource(R.string.home_cleared) else looking

/** The test tag of *Look up again* (#1112). */
const val LOOK_UP_AGAIN_TAG: String = "look-up-again"
