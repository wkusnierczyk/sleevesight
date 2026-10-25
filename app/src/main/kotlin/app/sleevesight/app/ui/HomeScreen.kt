package app.sleevesight.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.SleevesightTheme
import kotlinx.coroutines.launch

/**
 * Where the first-run flow lands: a header that stays at the top ([HomeHeader]), and under it, while
 * notification access is on, what is playing ([pipeline], the now-playing content) and, at the
 * bottom under a muted divider, the way back to what leaves the device (#441); while it is off, the
 * way to turn it on. Everything under the header scrolls, so a citation can bring its evidence line
 * into view.
 */
@Composable
fun HomeScreen(
    accessGranted: Boolean,
    onOpenFirstRun: () -> Unit,
    onOpenSettings: () -> Unit,
    pipeline: @Composable () -> Unit = {},
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // Animated under Compose's motion duration scale, which follows the phone's animator
            // duration scale: with animations off, Home is at the top at once.
            HomeHeader(scroll, onScrollToTop = { scope.launch { scroll.animateScrollTo(0) } }, onOpenSettings)
            Column(
                modifier =
                    Modifier
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (accessGranted) {
                    pipeline()
                    // Reference, read once: under what the user came for, set apart by a muted line.
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MUTED_ALPHA))
                    // A link, as every link is (#442), with a full touch target.
                    LinkLabel(
                        stringResource(R.string.home_what_leaves),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onOpenFirstRun).minimumInteractiveComponentSize(),
                    )
                } else {
                    Text(stringResource(R.string.home_access_off), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenFirstRun) { Text(stringResource(R.string.home_turn_on_access)) }
                }
            }
        }
    }
}

/**
 * Home's header (#445), pinned above what scrolls: the one-line logo ([HeaderLogo], #383), which
 * scrolls Home back to the top, and the cogwheel, which opens the settings. A divider under it
 * shows once Home has scrolled. A menu icon, if one comes, goes beside the cogwheel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeHeader(
    scroll: ScrollState,
    onScrollToTop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val toTop = stringResource(R.string.home_scroll_to_top, stringResource(R.string.app_name))
    val settings = stringResource(R.string.home_settings)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted, so the cogwheel is measured first and keeps its 48 dp at any font size; not
            // filled, so the tap target is the logo and not the row's empty middle.
            Box(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        // One button: the click and its role from `clickable`, the spoken label in
                        // place of the logo, which says nothing itself.
                        .clickable(role = Role.Button, onClick = onScrollToTop)
                        .clearAndSetSemantics { contentDescription = toTop }
                        .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The one-line logo (#383), in place of the name, which the label still says.
                HeaderLogo()
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(settings) } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = settings)
                }
            }
        }
        // Always laid out, so the content does not move when it shows.
        HorizontalDivider(color = if (scroll.canScrollBackward) DividerDefaults.color else Color.Transparent)
    }
}

/** How much of the divider's colour shows above *What leaves the device?*. */
private const val MUTED_ALPHA = 0.5f

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
