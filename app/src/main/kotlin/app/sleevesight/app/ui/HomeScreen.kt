package app.sleevesight.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.home.CoverPane
import app.sleevesight.app.ui.home.CoverSlot
import app.sleevesight.app.ui.home.HEADER_TRAVERSAL
import app.sleevesight.app.ui.home.HeaderPlayerFeed
import app.sleevesight.app.ui.home.LocalCoverPane
import app.sleevesight.app.ui.home.LogoAndRecord
import app.sleevesight.app.ui.home.NothingPlaying
import app.sleevesight.app.ui.home.ONE_COLUMN_MAX_WIDTH
import app.sleevesight.app.ui.home.PlayerSymbol
import app.sleevesight.app.ui.home.RecordButton
import app.sleevesight.app.ui.home.SPLIT_WIDTH
import app.sleevesight.app.ui.logo.HeaderLogo
import app.sleevesight.app.ui.logo.LogoSpin
import app.sleevesight.app.ui.report.ReportButtonState
import app.sleevesight.app.ui.report.TesterReportButton
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.LocalDeveloperMark
import app.sleevesight.app.ui.theme.MessageHost
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.developerMarkHeight
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Where the first-run flow lands: a header that stays at the top ([HomeHeader]), and under it, while
 * notification access is on, what is playing ([pipeline], the now-playing content) and, at the
 * bottom under a muted divider, the way back to what leaves the device (#441); while it is off, the
 * way to turn it on, with a line on Android's warning for it and what Sleevesight does with the access
 * (#708). Everything under the header scrolls, so a citation can bring its evidence line
 * into view.
 *
 * With [report] (#452: developer mode on, and *Report button* at *Show* or *Auto*), a small
 * report button floats at the bottom end, on Home only; what scrolls ends far enough below it that,
 * scrolled to the end, the button covers none of it. Under *Auto* (#518) it is hidden while Home
 * scrolls, and back once the scrolling stops ([TesterReportButton]).
 *
 * With [themeButton] (#636: *Theme button* on, the default), the header shows the theme button
 * before the cogwheel. With [logoSpin] (#848), the header logo's record turns while it says so and
 * notification access is on, as the followed player then says it plays. With [player] (#1409, #1209)
 * and access on, the record plays and pauses the followed player, and the player symbol opens the
 * player menu (#1440), or, with no player switched on, a panel that links to *Settings → Players*
 * ([onOpenSettingsAt]).
 */
@Suppress("LongParameterList") // The header's and the body's seams, each one of Home's parts.
@Composable
fun HomeScreen(
    accessGranted: Boolean,
    onOpenFirstRun: () -> Unit,
    onOpenSettings: () -> Unit,
    report: ReportButtonState? = null,
    themeButton: ThemeButtonState? = null,
    logoSpin: StateFlow<LogoSpin>? = null,
    player: HeaderPlayerFeed? = null,
    onOpenSettingsAt: (SettingsTarget) -> Unit = { onOpenSettings() },
    pipeline: @Composable () -> Unit = {},
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize()) {
        // The message after the guide's *Dismiss* (#759) at the bottom, over what scrolls, and
        // above the report button while that shows.
        val above = if (report != null) reportClearance() else 0.dp
        MessageHost(Modifier.fillMaxSize().safeDrawingPadding(), above) {
            // From [SPLIT_WIDTH] wide (#1097), by width whichever way the phone is held: the cover on
            // a side of its own, when there is one. The same parts either way, so what they keep
            // (the open panels, the scroll position) stays when the phone turns.
            BoxWithConstraints {
                val pane = remember { CoverPane() }.takeIf { maxWidth >= SPLIT_WIDTH }
                CompositionLocalProvider(LocalCoverPane provides pane) {
                    HomeFrame(
                        pane,
                        header = {
                            // Animated under Compose's motion duration scale, which follows the
                            // phone's animator duration scale: with animations off, Home is at the top at once.
                            // Read first, as in portrait, though the cover's side is to its left.
                            Box(if (pane != null) Modifier.semantics { headerFirst() } else Modifier) {
                                HomeHeader(
                                    scroll,
                                    onScrollToTop = { scope.launch { scroll.animateScrollTo(0) } },
                                    onOpenSettings,
                                    themeButton,
                                    if (accessGranted) logoSpin else null,
                                    if (accessGranted) player else null,
                                    onOpenPlayers = { onOpenSettingsAt(SettingsTarget.players) },
                                )
                            }
                        },
                        body = {
                            HomeBody(
                                scroll,
                                accessGranted,
                                onOpenFirstRun,
                                reportShown = report != null,
                                pipeline,
                            )
                        },
                    )
                }
            }
            report?.let {
                TesterReportButton(
                    it,
                    scrolling = { scroll.isScrollInProgress },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(REPORT_MARGIN),
                )
            }
        }
    }
}

/**
 * Home's parts in place: the [header] over the [body], what scrolls; from [SPLIT_WIDTH] wide ([pane]
 * not null, #1097), with a track's cover on the left on a side of its own ([CoverSlot]), as tall as
 * Home and at most half its width, and the header and the body to its right. With no cover to show
 * (nothing playing, an ad), the header and the body take the whole width, as in portrait. The cover's
 * side is composed last, so the cover a track puts there ([CoverInPane]) shows in the same frame.
 */
@Composable
private fun HomeFrame(
    pane: CoverPane?,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Layout(
        contents =
            listOf(header, body, {
                if (pane !=
                    null
                ) {
                    CoverSlot(pane)
                }
            }),
    ) { (heads, bodies, covers), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cover = covers.firstOrNull()?.measure(Constraints(maxWidth = width / 2, maxHeight = height))
        val left = cover?.width ?: 0
        val right = width - left
        val head = heads.first().measure(Constraints(minWidth = right, maxWidth = right, maxHeight = height))
        val rest = bodies.first().measure(Constraints.fixed(right, (height - head.height).coerceAtLeast(0)))
        // Placed in the order a screen reader meets them, the header first.
        layout(width, height) {
            head.placeRelative(left, 0)
            cover?.placeRelative(0, 0)
            rest.placeRelative(left, head.height)
        }
    }
}

// What scrolls under the header: while access is on, what is playing and the way back to what
// leaves the device; while it is off, the way to turn it on. It ends below the report button when
// that shows, under *Auto* too, so scrolled to the end, where the button is back, it covers nothing.
// Split in two with no cover to show ([LocalCoverPane] empty, #1097: nothing playing), one column at
// most [ONE_COLUMN_MAX_WIDTH] wide, centred.
@Composable
private fun HomeBody(
    scroll: ScrollState,
    accessGranted: Boolean,
    onOpenFirstRun: () -> Unit,
    reportShown: Boolean,
    pipeline: @Composable () -> Unit,
) {
    val pane = LocalCoverPane.current
    Column(
        modifier =
            Modifier
                // Split in two, what scrolls is read in the screen's order, not as a group of its own,
                // so the header comes before the cover's side, and the title after (#1097).
                .then(if (pane != null) Modifier.semantics { isTraversalGroup = false } else Modifier)
                .verticalScroll(scroll)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (reportShown) reportClearance() else 8.dp,
                ).oneColumnWithoutCover(pane),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (accessGranted) {
            pipeline()
            // Reference, read once: under what the user came for, set apart by a muted line.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MUTED_ALPHA))
            // A link, as every link is (#442), with a full touch target.
            LinkLabel(
                TextPart.Action(stringResource(R.string.home_what_leaves), WHAT_LEAVES_TAG, onOpenFirstRun),
                onClickLabel = stringResource(R.string.home_what_leaves_open),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(stringResource(R.string.home_access_off), style = MaterialTheme.typography.bodyLarge)
            // The first-run screen, which the button opens, says it in full before Android's own screen
            // for the access (#708); here, the short form.
            Text(stringResource(R.string.home_access_statement), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenFirstRun) { Text(stringResource(R.string.home_turn_on_access)) }
        }
    }
}

/**
 * At most [ONE_COLUMN_MAX_WIDTH] wide, centred, while Home is split in two and [pane] shows no cover;
 * as wide as given otherwise. Read as it is laid out, once the track has put its cover there or not.
 */
private fun Modifier.oneColumnWithoutCover(pane: CoverPane?): Modifier =
    layout { measurable, constraints ->
        val cap = if (pane != null && pane.content == null) ONE_COLUMN_MAX_WIDTH.roundToPx() else constraints.maxWidth
        val width = min(constraints.maxWidth, cap)
        val placeable =
            measurable.measure(
                constraints.copy(minWidth = min(constraints.minWidth, width), maxWidth = width),
            )
        val outer = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(outer, placeable.height) { placeable.placeRelative((outer - placeable.width) / 2, 0) }
    }

// The header as a screen reader reads it on Home split in two (#1097): one group, first.
private fun SemanticsPropertyReceiver.headerFirst() {
    isTraversalGroup = true
    traversalIndex = HEADER_TRAVERSAL
}

/**
 * Home's header (#445), pinned above what scrolls: the one-line logo ([HeaderLogo], #383), whose words
 * scroll Home back to the top, and whose record is a button of its own ([RecordButton], #1409), which
 * plays and pauses the followed player ([player]), and does nothing on a long press; and at the end
 * the player symbol, which opens the player menu (#1209), or a panel with [onOpenPlayers]' link, and
 * the player on a long press ([PlayerSymbol], #1440), the theme button ([themeButton], #636), which
 * opens the theme menu (#771), while *Theme button* is on, and the cogwheel, which opens the
 * settings. The logo's record turns as [logoSpin] says (#848). A divider under it shows once Home has scrolled.
 */
@Suppress("LongParameterList") // The header's parts, each on its own.
@Composable
private fun HomeHeader(
    scroll: ScrollState,
    onScrollToTop: () -> Unit,
    onOpenSettings: () -> Unit,
    themeButton: ThemeButtonState?,
    logoSpin: StateFlow<LogoSpin>?,
    player: HeaderPlayerFeed?,
    onOpenPlayers: () -> Unit,
) {
    val spin = logoSpin?.collectAsState()?.value ?: LogoSpin.STILL
    // The header's box, which a debug build's bug crawls out of and back into (#848).
    var box by remember { mutableStateOf<Rect?>(null) }
    val toTop = stringResource(R.string.home_scroll_to_top, stringResource(R.string.app_name))
    val settings = stringResource(R.string.home_settings)
    Column {
        Row(
            modifier =
                Modifier
                    .testTag(HOME_HEADER_TAG)
                    .onGloballyPositioned { box = it.boundsInRoot() }
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted, so the buttons are measured first and keep their 48 dp at any font size; not
            // filled, so the tap target is the logo and not the row's empty middle.
            LogoAndRecord(spin, { box }, toTop, onScrollToTop, player, Modifier.weight(1f, fill = false))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The player symbol (#1440), in place of the player's own icon after the logo (#1209).
                player?.let { PlayerSymbol(it, onOpenPlayers) }
                // The current theme, named and drawn, which opens the theme menu (#771); the cogwheel
                // stays at the end.
                themeButton?.let { ThemeMenuButton(it) }
                HeaderButton(settings, R.drawable.ic_settings, onOpenSettings)
            }
        }
        // Always laid out, so the content does not move when it shows.
        HorizontalDivider(color = if (scroll.canScrollBackward) DividerDefaults.color else Color.Transparent)
    }
}

/** A button of Home's header: [icon], 48 dp, with [label] as its content description and its tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeaderButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

/** The report button's margin from the screen's edges. */
private val REPORT_MARGIN = 16.dp

/** [REPORT_CLEARANCE]; marked (#536), the button is taller by the mark, which follows the font size. */
@Composable
private fun reportClearance(): Dp =
    if (LocalDeveloperMark.current) REPORT_CLEARANCE + developerMarkHeight() else REPORT_CLEARANCE

/**
 * The space under what scrolls, and under a message (#759), while the report button shows: the
 * small button's 40 dp, its margin below and 16 dp above it, so the end of Home scrolls clear of it.
 */
private val REPORT_CLEARANCE = 72.dp

/** The test tag of Home's header row, the logo and the buttons. */
const val HOME_HEADER_TAG: String = "home-header"

/** The link tag of *What leaves the device?* on Home. */
const val WHAT_LEAVES_TAG: String = "what-leaves"

/** How much of the divider's colour shows above *What leaves the device?*. */
private const val MUTED_ALPHA = 0.5f

@Preview(showBackground = true)
@Composable
private fun HomeAccessOnPreview() {
    SleevesightTheme {
        HomeScreen(accessGranted = true, onOpenFirstRun = {}, onOpenSettings = {}) { NothingPlaying() }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeAccessOffPreview() {
    SleevesightTheme { HomeScreen(accessGranted = false, onOpenFirstRun = {}, onOpenSettings = {}) }
}
