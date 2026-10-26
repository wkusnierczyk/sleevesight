package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// Links from one setting to another (#539): a link in a row's text that unfolds a section of the
// settings and lands on a row in it, as the key links of *AI services* and *Sources* do. To add one:
//
// 1. Name the place: a [SettingsTarget], the section and a row id of its own, best as a factory
//    in its companion, as [SettingsTarget.key] is; a target without a row lands on the section's
//    heading, which every [FoldedSection] already is.
// 2. Wrap the row landed on in [LandingSpot] with that target.
// 3. Put the link in the row's text: [settingsLink] gives the [TextPart.Action], for a
//    [LinkedText] (with [sentenceWith] to set it in a sentence), as [KeyLink] does.

/**
 * Where a link in the settings leads (#539): [section], unfolded, and in it the row [row] names, or,
 * for none, the section's heading. A row is named by an id of its own, unique among the screen's
 * rows; the factories in the companion name the rows that are linked to.
 */
@Immutable
internal data class SettingsTarget(
    val section: SettingsSection,
    val row: String? = null,
) {
    /** The tag of the row landed on, for tests; a section's own is its heading's ([SettingsSection.tag]). */
    val tag: String get() = row?.let { "settings_row_$it" } ?: section.tag

    /** The tag of a link to it, which [TextPart.Action] names it by to tests. */
    val linkTag: String get() = "settings_link_$tag"

    companion object {
        /** The key [id]'s row under *Secrets* ([KeyRow]). */
        fun key(id: SecretId): SettingsTarget = SettingsTarget(SettingsSection.SECRETS, "key_${id.name}")
    }
}

/**
 * Follows the settings' links (#539): [go] unfolds the target's section in [folds] and lands on the
 * target, which its [LandingSpot] then scrolls into view and focuses. One per visit to the
 * settings, given to the rows as [LocalSettingsNavigator] ([ProvideSettingsNavigator]).
 */
@Stable
internal class SettingsNavigator(
    private val folds: SectionFolds,
) {
    /** The landing under way, until its spot has landed it; null for none. */
    var landing: Landing? by mutableStateOf(null)
        private set

    private var count = 0

    /** Unfolds [target]'s section, if folded, and lands on [target]. */
    fun go(target: SettingsTarget) {
        folds.unfold(target.section)
        landing = Landing(target, ++count)
    }

    /** Ends [done], unless another landing has begun since. */
    fun landed(done: Landing) {
        if (landing == done) landing = null
    }
}

/** A landing on [target]; [count] tells one from the next, so the same link followed again lands again. */
internal data class Landing(
    val target: SettingsTarget,
    val count: Int,
)

/**
 * The settings' [SettingsNavigator]; null outside the settings screen, where a link to a setting
 * does nothing and a [LandingSpot] is only its row.
 */
internal val LocalSettingsNavigator = staticCompositionLocalOf<SettingsNavigator?> { null }

/** [content] with a [SettingsNavigator] over [folds], for this visit to the settings. */
@Composable
internal fun ProvideSettingsNavigator(
    folds: SectionFolds,
    content: @Composable () -> Unit,
) {
    val navigator = remember(folds) { SettingsNavigator(folds) }
    CompositionLocalProvider(LocalSettingsNavigator provides navigator, content = content)
}

/**
 * A link, [text], that unfolds [target]'s section and lands on [target] ([SettingsNavigator.go]),
 * marked as every link is, since it is drawn by [LinkedText] (#442, #519). Its text should name
 * what it leads to, as a screen reader reads a link by its text alone: "Anthropic API key", not
 * "here".
 */
@Composable
internal fun settingsLink(
    text: String,
    target: SettingsTarget,
): TextPart.Action {
    val navigator = LocalSettingsNavigator.current
    return TextPart.Action(text, target.linkTag) { navigator?.go(target) }
}

/**
 * [sentence] with [link] in place of its text, where the sentence has it (the last place, should it
 * be there twice), for a [LinkedText]; a sentence without it is plain words, followed by nothing.
 */
internal fun sentenceWith(
    sentence: String,
    link: TextPart.Linked,
): List<TextPart> {
    val at = sentence.lastIndexOf(link.text)
    if (at < 0 || link.text.isEmpty()) return listOf(TextPart.Words(sentence))
    return listOf(
        TextPart.Words(sentence.substring(0, at)),
        link,
        TextPart.Words(sentence.substring(at + link.text.length)),
    ).filter { it.text.isNotEmpty() }
}

/**
 * [content], a row, as where a link to [target] lands (#539): when one is followed, the row is
 * scrolled into view once laid out, and takes the focus, so a keyboard, and a screen reader, which
 * follows the focus, go on from it. The focus shows as the row's focus highlight; a row that would
 * take no focus while the phone is touched rather than driven by a keyboard (Compose's clickable
 * rows) is let take it for [LANDING], a moment to see where the link led, and then lets it go, as
 * Home's citations flash the line they lead to. The tag of a target with a row
 * ([SettingsTarget.tag]) is on the spot, for tests.
 */
@Composable
internal fun LandingSpot(
    target: SettingsTarget,
    content: @Composable () -> Unit,
) {
    val navigator = LocalSettingsNavigator.current
    val landing = navigator?.landing?.takeIf { it.target == target }
    val view = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    // Whether the row holds the focus, read only by the landing, so a plain var.
    val held = remember { BooleanArray(1) }
    LaunchedEffect(navigator, landing) {
        if (navigator == null || landing == null) return@LaunchedEffect
        try {
            // The row may have been folded away until the link was followed: it is laid out in
            // the next frame.
            withFrameNanos { }
            view.bringIntoView()
            focus.requestFocus()
            delay(LANDING)
            // Touched, the row lets the focus go, unless it has moved on meanwhile; driven by a
            // keyboard, it keeps it, to go on from.
            if (held[0] && inputModes.inputMode == InputMode.Touch) focusManager.clearFocus()
        } finally {
            navigator.landed(landing)
        }
    }
    Column(
        modifier =
            Modifier
                .let { if (target.row != null) it.testTag(target.tag) else it }
                .bringIntoViewRequester(view)
                // Only while landing: otherwise the row's own focus rules hold.
                .focusProperties { if (landing != null) canFocus = true }
                .focusRequester(focus)
                .onFocusChanged { held[0] = it.hasFocus },
    ) {
        content()
    }
}

/** How long a landed row may keep the focus it takes in touch mode ([LandingSpot]). */
private val LANDING = 1300.milliseconds

/**
 * Under a service that needs the key [id] (#539): "Set your Anthropic API key here.", the key's name
 * a link that unfolds *Secrets* and lands on the key's row, shown whether or not a key is saved, so
 * the key is always one tap away; with one saved, the row there changes it.
 */
@Composable
internal fun KeyLink(id: SecretId) {
    val name = stringResource(keyName(id))
    LinkedText(
        sentenceWith(stringResource(R.string.settings_set_key, name), settingsLink(name, SettingsTarget.key(id))),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
