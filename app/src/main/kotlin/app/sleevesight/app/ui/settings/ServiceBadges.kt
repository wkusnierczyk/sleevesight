package app.sleevesight.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.SourceSwitch

// A service's badge under *Sources*, *Genres*, *AI services* and, under *History*, how long each
// source's findings are kept (#537, #670): its letter in a plain, neutral tile, the size of a
// player's icon under *Players*, so the rows line up. Letters, not the services' logos: each logo
// is a third party's mark with its own terms (#591).

/**
 * The size of what starts a settings row: a player's icon (#535), a service's badge (#537); the
 * size Android's own app lists draw an app's icon at.
 */
internal val ROW_MARK = 40.dp

/**
 * The size of a badge's letters: fixed in dp, not scaled with the phone's font size, as the badge
 * and a player's icon are not; the name beside it scales. Two letters (`MB`) fit the badge.
 */
private val BADGE_TEXT = 15.dp

/** The badge's corners: those of Home's source chips. */
private val BADGE_SHAPE = RoundedCornerShape(8.dp)

/** The letter [source]'s citations and Home's chip use: its [SourceId.tag], the one place it is set. */
internal fun badgeLetter(source: SourceId): String = source.tag

/**
 * The letter a source's citations and Home's chip use for [switch]: its first source's, so
 * *Wikipedia and Wikidata* is `W`.
 */
internal fun badgeLetter(switch: SourceSwitch): String = badgeLetter(switch.sources.first())

/** The letter a web search engine's badge shows: that of web search's citations, `S` (#769). */
internal val WEB_BADGE: String = badgeLetter(SourceSwitch.WEB)

/** The badge of an AI service named [name]: its initial. */
internal fun providerBadge(name: String): String = name.take(1)

/**
 * A service's badge showing [letter], at a row's start ([SwitchRow]'s `leading`). Decorative: a
 * screen reader skips it, since the name beside it says which service it is. Its letters are set
 * in the app's type (*Font*) and colours from the theme, so it follows both, and light and dark.
 */
@Composable
internal fun ServiceBadge(letter: String) {
    val colors = MaterialTheme.colorScheme
    val size = with(LocalDensity.current) { BADGE_TEXT.toSp() }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .testTag(BADGE_PREFIX + letter)
                .size(ROW_MARK)
                .background(colors.surfaceContainerHighest, BADGE_SHAPE)
                .border(1.dp, colors.outlineVariant, BADGE_SHAPE)
                .clearAndSetSemantics {},
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = size, lineHeight = size),
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The test tag of a service's badge, before its letter (`MB`, `A`). */
const val BADGE_PREFIX: String = "badge:"
