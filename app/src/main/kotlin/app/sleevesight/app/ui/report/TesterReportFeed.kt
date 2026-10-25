package app.sleevesight.app.ui.report

import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow

/**
 * What Home's report button (#452) reads: the [settings], whose *Developer mode* and *Report
 * button* both on show it; the [reports], which take what the app knows at the tap; and [mail],
 * where a report goes, null for the phone's own mail apps ([PhoneMail]).
 */
class TesterReportFeed(
    val settings: Flow<Settings>,
    val reports: TesterReports,
    val mail: MailHandoff? = null,
)

/**
 * [content] (Home) given what its report button does: null while the button is not shown (no
 * [feed], or developer mode or *Report button* off), else a tap that takes what the app knows then
 * and opens its review ([TesterReportDialog]). The review outlives the button: turned off meanwhile,
 * an open review stays until the tester closes it.
 */
@Composable
fun WithTesterReport(
    feed: TesterReportFeed?,
    content: @Composable (onReport: (() -> Unit)?) -> Unit,
) {
    if (feed == null) {
        content(null)
        return
    }
    val settings by feed.settings.collectAsState(initial = null)
    var open by remember { mutableStateOf<TesterSnapshot?>(null) }
    val shown = settings?.let { it.developerMode && it.reportButton } == true
    content(if (shown) ({ open = feed.reports.snapshot(settings) }) else null)
    open?.let { snapshot ->
        val context = LocalContext.current
        val handoff = feed.mail ?: remember(context) { PhoneMail(context) }
        TesterReportDialog(snapshot, { feed.reports.history(snapshot) }, handoff) { open = null }
    }
}

/** Home's report button: small, with what it does for a screen reader. */
@Composable
fun TesterReportButton(
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SmallFloatingActionButton(onClick = onReport, modifier = modifier.testTag(TESTER_REPORT_TAG)) {
        Icon(
            painterResource(R.drawable.ic_bug_report),
            contentDescription = stringResource(R.string.tester_report_button),
        )
    }
}

/** The test tag of Home's report button. */
const val TESTER_REPORT_TAG: String = "tester_report"
