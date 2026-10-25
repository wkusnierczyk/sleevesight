package app.sleevesight.app.ui.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.PhoneMail
import app.sleevesight.app.report.TesterReports
import app.sleevesight.app.report.TesterSnapshot
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow

/**
 * What Home's report button (#452) reads: the [settings], whose *Developer mode* on and *Report
 * button* other than *Hide* show it; [hide], which sets *Report button* to *Hide* (#518), from the
 * button's menu; the [reports], which take what the app knows at the tap; and [mail], where a
 * report goes, null for the phone's own mail apps ([PhoneMail]).
 */
class TesterReportFeed(
    val settings: Flow<Settings>,
    val hide: () -> Unit,
    val reports: TesterReports,
    val mail: MailHandoff? = null,
)

/**
 * Home's report button as *Report button* has it (#518): [hideWhileScrolling] under *Auto*;
 * [onReport] takes what the app knows then and opens its review, and [onHide] sets *Hide*.
 */
class ReportButtonState(
    val hideWhileScrolling: Boolean,
    val onReport: () -> Unit,
    val onHide: () -> Unit,
)

/**
 * [content] (Home) given its report button: null while the button is not shown (no [feed], or
 * developer mode off, or *Report button* at *Hide*), else what the button does ([ReportButtonState]).
 * The review outlives the button: hidden meanwhile, an open review stays until the tester closes
 * it. So does the message the menu's *Hide* shows, which says where to bring the button back.
 */
@Composable
fun WithTesterReport(
    feed: TesterReportFeed?,
    content: @Composable (report: ReportButtonState?) -> Unit,
) {
    if (feed == null) {
        content(null)
        return
    }
    val settings by feed.settings.collectAsState(initial = null)
    var open by remember { mutableStateOf<TesterSnapshot?>(null) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    val choice = settings?.takeIf { it.developerMode }?.reportButton ?: ReportButton.HIDE
    content(
        if (choice == ReportButton.HIDE) {
            null
        } else {
            ReportButtonState(
                hideWhileScrolling = choice == ReportButton.AUTO,
                onReport = { open = feed.reports.snapshot(settings) },
                onHide = {
                    feed.hide()
                    hidden = true
                },
            )
        },
    )
    open?.let { snapshot ->
        val context = LocalContext.current
        val handoff = feed.mail ?: remember(context) { PhoneMail(context) }
        TesterReportDialog(snapshot, { feed.reports.history(snapshot) }, handoff) { open = null }
    }
    if (hidden) ReportButtonHiddenDialog { hidden = false }
}

/**
 * Home's report button: small, with what it does for a screen reader. A tap opens its menu (#518):
 * *Report*, the review, and *Hide*. Under *Auto* ([ReportButtonState.hideWhileScrolling]) it fades
 * and slides away while [scrolling] says Home scrolls, and back once it stops, unless its menu is
 * open; with the phone's animations off, at once, as Compose's animations follow the animator
 * duration scale.
 */
@Composable
fun TesterReportButton(
    state: ReportButtonState,
    scrolling: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = menu || !(state.hideWhileScrolling && scrolling()),
        modifier = modifier,
        enter = fadeIn(tween(SHOW_MILLIS)) + slideInVertically(tween(SHOW_MILLIS)) { it / 2 },
        exit = fadeOut(tween(HIDE_MILLIS)) + slideOutVertically(tween(HIDE_MILLIS)) { it / 2 },
    ) {
        Box {
            SmallFloatingActionButton(onClick = { menu = true }, modifier = Modifier.testTag(TESTER_REPORT_TAG)) {
                Icon(
                    painterResource(R.drawable.ic_bug_report),
                    contentDescription = stringResource(R.string.tester_report_button),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tester_report_menu_report)) },
                    onClick = {
                        menu = false
                        state.onReport()
                    },
                    modifier = Modifier.testTag(TESTER_REPORT_MENU_REPORT_TAG),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tester_report_menu_hide)) },
                    onClick = {
                        menu = false
                        state.onHide()
                    },
                    modifier = Modifier.testTag(TESTER_REPORT_MENU_HIDE_TAG),
                )
            }
        }
    }
}

/** After the menu's *Hide* (#518): where to bring the button back, and *Dismiss*. */
@Composable
private fun ReportButtonHiddenDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.tester_report_hidden)) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TESTER_REPORT_HIDDEN_DISMISS_TAG)) {
                Text(stringResource(R.string.tester_report_hidden_dismiss))
            }
        },
        modifier = Modifier.testTag(TESTER_REPORT_HIDDEN_TAG),
    )
}

/** How long the button takes to come back under *Auto*, once Home stops scrolling. */
private const val SHOW_MILLIS = 200

/** How long the button takes to go under *Auto* once Home starts scrolling: out of the way soon. */
private const val HIDE_MILLIS = 120

/** The test tag of Home's report button. */
const val TESTER_REPORT_TAG: String = "tester_report"

/** The test tag of the report button's menu item *Report* (#518). */
const val TESTER_REPORT_MENU_REPORT_TAG: String = "tester_report_menu_report"

/** The test tag of the report button's menu item *Hide* (#518). */
const val TESTER_REPORT_MENU_HIDE_TAG: String = "tester_report_menu_hide"

/** The test tag of the message after *Hide* (#518). */
const val TESTER_REPORT_HIDDEN_TAG: String = "tester_report_hidden"

/** The test tag of that message's *Dismiss*. */
const val TESTER_REPORT_HIDDEN_DISMISS_TAG: String = "tester_report_hidden_dismiss"
