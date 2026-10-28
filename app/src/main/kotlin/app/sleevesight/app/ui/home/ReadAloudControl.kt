package app.sleevesight.app.ui.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.speech.ReadAloud

/**
 * A *Read aloud* button's state (#325): whether its own reading is under way ([reading]); a tap
 * starts it ([onRead]) or, while it reads, stops it ([onStop]).
 */
internal class ReadAloudControl(
    val reading: Boolean,
    val onRead: () -> Unit,
    val onStop: () -> Unit,
)

/**
 * The control of the reading [id] of [readAloud] ([app.sleevesight.app.speech.Reading.INSIGHT], a
 * genre's reason), which a tap starts with [start]; null with no [readAloud] (a screen without it),
 * when no button shows.
 */
@Composable
internal fun readAloudControl(
    readAloud: ReadAloud?,
    id: String,
    start: () -> Unit,
): ReadAloudControl? {
    if (readAloud == null) return null
    val reading by readAloud.reading.collectAsState()
    return ReadAloudControl(reading?.id == id, start, readAloud::stop)
}

/**
 * *Read aloud* (#325): a plain 48 dp icon button, Material Symbols' speaker (`volume_up`), named
 * *Read aloud* to a screen reader and in its tooltip; while its reading is under way, a stop symbol
 * in a circle (`stop_circle`), named *Stop reading aloud*, which stops it at once. It makes no call
 * to the AI service, so it carries no **AI** mark. [tag] is its test tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadAloudButton(
    control: ReadAloudControl,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (control.reading) R.string.home_read_aloud_stop else R.string.home_read_aloud)
    val icon = if (control.reading) R.drawable.ic_stop_circle else R.drawable.ic_volume_up
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = if (control.reading) control.onStop else control.onRead,
            modifier = Modifier.testTag(tag),
        ) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}

/** The test tag of the insight card's *Read aloud*. */
const val READ_ALOUD_TAG: String = "read-aloud"

/** The test tag of *Read aloud* under a genre's reason. */
const val GENRE_READ_ALOUD_TAG: String = "genre-panel:read-aloud"
