package app.sleevesight.app.ui.settings

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.moveAccessibilityFocusToFocused
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// Links from one setting to another (#539): a link in a row's text that opens a section of the
// settings (its panel, #649) and lands on a row in it, as the key links of *AI services* and *Sources* do. To add one:
//
// 1. Name the place: a [SettingsTarget], the section and a row id of its own, best as a factory
//    in its companion, as [SettingsTarget.key] is; a target without a row lands on the heading of
//    the section's panel, which every [FoldedSection] already is.
// 2. Wrap the row landed on in [LandingSpot] with that target.
// 3. Put the link in the row's text: [settingsLink] gives the [TextPart.Action], for a
//    [LinkedText] (with [sentenceWith] to set it in a sentence), as [KeyLink] does.

/**
 * Where a link in the settings leads (#539): [section], opened, and in it the row [row] names, or,
 * for none, the heading of the section's panel (#649), whose *Close* takes the focus. A row is
 * named by an id of its own, unique among the screen's rows; the factories in the companion name
 * the rows that are linked to. Public, as Home opens the settings at one (#581: [SettingsScreen]'s
 * `landOn`, through the settings' route).
 */
@Immutable
data class SettingsTarget(
    val section: SettingsSection,
    val row: String? = null,
) {
    /** The tag of the row landed on, for tests; a section's own is its chip's ([SettingsSection.tag]). */
    val tag: String get() = row?.let { "settings_row_$it" } ?: section.tag

    /** The tag of a link to it, which [TextPart.Action] names it by to tests. */
    val linkTag: String get() = "settings_link_$tag"

    companion object {
        /** The key [id]'s row under *Secrets* ([KeyRow]). */
        internal fun key(id: SecretId): SettingsTarget = SettingsTarget(SettingsSection.SECRETS, "key_${id.name}")

        /** The section *AI services* itself, its panel's heading (#581). */
        internal val aiServices: SettingsTarget = SettingsTarget(SettingsSection.AI_SERVICES)

        /** [provider]'s model row under *AI services* (#581), where another model is chosen. */
        internal fun model(provider: ProviderId): SettingsTarget =
            SettingsTarget(SettingsSection.AI_SERVICES, "model_${provider.name}")

        /** *New session idle trigger*'s row under *History* (#581). */
        internal val sessionGap: SettingsTarget = SettingsTarget(SettingsSection.HISTORY, "session_gap")

        /** *Reactive*'s row under *Insights*, from the insight card's menu of its modes (#615). */
        internal val reactive: SettingsTarget = SettingsTarget(SettingsSection.INSIGHTS, "reactive")

        /** The section *Players* itself, its panel's heading, from Home's list of players (#627). */
        internal val players: SettingsTarget = SettingsTarget(SettingsSection.PLAYERS)

        /** The section *Sources* itself, its panel's heading, from a panel on Home whose source is off (#724). */
        internal val sources: SettingsTarget = SettingsTarget(SettingsSection.SOURCES)

        /** *Search Genius when there is no link*'s row under *Appearance*, from Home's *Lyrics* (#728). */
        internal val lyricsSearch: SettingsTarget = SettingsTarget(SettingsSection.APPEARANCE, "lyrics_search")

        /**
         * The section *Genres* itself, its panel's heading, from *Genres* on Home when the label
         * sources are on but their genre switches are off (#772).
         */
        internal val genres: SettingsTarget = SettingsTarget(SettingsSection.GENRES)

        /**
         * The target [section] (a [SettingsSection]'s name) and [row] name, as a route or a saved
         * state carries it; null for no section, or a name no section has.
         */
        internal fun named(
            section: String?,
            row: String?,
        ): SettingsTarget? =
            SettingsSection.entries
                .firstOrNull { it.name == section }
                ?.let { SettingsTarget(it, row?.ifEmpty { null }) }
    }
}

/**
 * Follows the settings' links (#539): [go] opens the target's section in [folds] and lands on the
 * target, which its [LandingSpot] then scrolls into view and focuses. One per visit to the
 * settings, given to the rows as [LocalSettingsNavigator] ([ProvideSettingsNavigator]). Only a
 * landing still scrolling is saved with the screen ([saver]), to scroll and land once the screen is
 * recreated (a rotation); once the row has the focus nothing is pending, so leaving the settings
 * and coming back, or a recreation after it, lands on nothing.
 */
@Stable
internal class SettingsNavigator(
    private val folds: SectionFolds,
) {
    /** The landing under way, until its spot has landed it; null for none. */
    var landing: Landing? by mutableStateOf(null)
        private set

    /**
     * Whether the landing's own scroll is still to come or under way, which alone [LandingScroll]
     * brings to a quarter of the way down; not its focus or flash after it. Read only when the column
     * scrolls, so not state.
     */
    var scrolling: Boolean = false
        private set

    private var count = 0

    // The targets a [LandingSpot] is drawn for, which a landing can reach; read only by [dropUnclaimed].
    private val spots = mutableSetOf<SettingsTarget>()

    /** [target]'s [LandingSpot] is drawn, until [release]d. */
    fun claim(target: SettingsTarget) {
        spots += target
    }

    /** [target]'s [LandingSpot] is no longer drawn. */
    fun release(target: SettingsTarget) {
        spots -= target
    }

    /**
     * Ends [pending], unless another landing has begun since, where no [LandingSpot] is drawn for
     * its target once its section is unfolded: a route or a saved state naming a row no longer shown
     * (#581) would otherwise leave it pending, and [scrolling] set, for the rest of the visit.
     * Called a frame after the landing begins, which is enough only because each [FoldedSection]
     * composes all its rows at once, in a column that is not lazy: a lazy one would draw a row, and
     * claim its spot, only once it is scrolled near, and this would drop a landing still to come.
     */
    fun dropUnclaimed(pending: Landing) {
        if (pending.target !in spots) landed(pending)
    }

    /** Opens [target]'s section, if closed, and lands on [target]. */
    fun go(target: SettingsTarget) {
        folds.unfold(target.section)
        resume(target)
    }

    /** Lands on [target], whose section is unfolded. */
    private fun resume(target: SettingsTarget) {
        landing = Landing(target, ++count)
        scrolling = true
    }

    /** Ends [done]'s own scroll, unless another landing has begun since. */
    fun scrolled(done: Landing) {
        if (landing == done) scrolling = false
    }

    /** Ends [done], unless another landing has begun since. */
    fun landed(done: Landing) {
        if (landing == done) {
            landing = null
            scrolling = false
        }
    }

    companion object {
        /**
         * Saves the target of a landing still scrolling ([scrolling]), for a navigator over [folds]
         * to land on again, if its section is still unfolded there; nothing otherwise.
         */
        fun saver(folds: SectionFolds): Saver<SettingsNavigator, ArrayList<String>> =
            Saver(
                save = { navigator ->
                    navigator.landing
                        ?.takeIf { navigator.scrolling }
                        ?.target
                        ?.let { arrayListOf(it.section.name, it.row.orEmpty()) }
                },
                restore = { saved ->
                    SettingsNavigator(folds).also { navigator ->
                        SettingsTarget
                            .named(saved[0], saved[1])
                            ?.takeIf { folds.isUnfolded(it.section) }
                            ?.let { navigator.resume(it) }
                    }
                },
            )
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
    val navigator = rememberSaveable(folds, saver = SettingsNavigator.saver(folds)) { SettingsNavigator(folds) }
    val landing = navigator.landing
    LaunchedEffect(navigator, landing) {
        if (landing == null) return@LaunchedEffect
        // The section unfolded and its rows drawn, each spot has claimed its target by the next frame.
        withFrameNanos { }
        navigator.dropUnclaimed(landing)
    }
    CompositionLocalProvider(LocalSettingsNavigator provides navigator, content = content)
}

/**
 * The settings' scrolling column, [content] under [modifier] (which comes after the scroll), which
 * brings a landed row a quarter of the way down ([LandingScroll]). Only the column itself scrolls
 * so: anything in [content] that scrolls on its own does as usual. `LocalBringIntoViewSpec` is
 * experimental in Compose; without it, a landed row would have to be scrolled by hand from its
 * measured place in the column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsScrollColumn(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical,
    content: @Composable ColumnScope.() -> Unit,
) {
    val navigator = LocalSettingsNavigator.current
    val usual = LocalBringIntoViewSpec.current
    val landing = remember(navigator, usual) { navigator?.let { LandingScroll(it, usual) } ?: usual }
    CompositionLocalProvider(LocalBringIntoViewSpec provides landing) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).then(modifier),
            verticalArrangement = verticalArrangement,
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides usual) { content() }
        }
    }
}

/**
 * How the settings' column brings a row into view: for a link's landing ([SettingsNavigator.scrolling]),
 * with the row a quarter of the way down ([LANDING_KEYLINE]), where the eye looks for it, rather than
 * at the bottom edge, as the least scroll that shows it would leave it; else as [usual], so moving
 * through the rows with a keyboard, even during the landing's flash, scrolls only as far as it needs.
 * The column cannot scroll past its ends, so a row near the end of the list stays lower.
 */
private class LandingScroll(
    private val navigator: SettingsNavigator,
    private val usual: BringIntoViewSpec,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float =
        if (navigator.scrolling) {
            offset - containerSize * LANDING_KEYLINE
        } else {
            usual.calculateScrollDistance(offset, size, containerSize)
        }
}

/** Where a landed row's top comes to rest, as a fraction of the column's height from its top. */
private const val LANDING_KEYLINE = 0.25f

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
 * scrolled, once laid out, to a quarter of the way down the column ([LandingScroll]), and takes the
 * focus, so a keyboard goes on from it; while a screen reader explores by touch (TalkBack), the
 * row also takes its focus, which Compose does not move with the input focus
 * ([moveAccessibilityFocusToFocused]), so the screen reader reads the row and goes on from it. The
 * focus shows as the row's focus highlight; a row that
 * would take no focus while the phone is touched rather than driven by a keyboard (Compose's
 * clickable rows) is let take it for [LANDING], a moment to see where the link led, and then lets it
 * go, as Home's citations flash the line they lead to; it keeps it while a screen reader explores by
 * touch. The tag of a target with a row
 * ([SettingsTarget.tag]) is on the spot, for tests.
 */
@Composable
internal fun LandingSpot(
    target: SettingsTarget,
    content: @Composable () -> Unit,
) {
    val navigator = LocalSettingsNavigator.current
    val landing = navigator?.landing?.takeIf { it.target == target }
    val relocation = remember { BringIntoViewRequester() }
    val host = LocalView.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    // Whether the row holds the focus, and where it is laid out: read only by the landing, so plain.
    val held = remember { BooleanArray(1) }
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(navigator, target) {
        navigator?.claim(target)
        onDispose { navigator?.release(target) }
    }
    LaunchedEffect(navigator, landing) {
        if (navigator == null || landing == null) return@LaunchedEffect
        try {
            // The row may have been folded away until the link was followed: it is laid out in
            // the next frame.
            withFrameNanos { }
            try {
                relocation.bringIntoView()
            } finally {
                navigator.scrolled(landing)
            }
            // The scroll's end is not its arrival: a drag can cut it short. Unless the landing is still
            // this one and the row is in view, it gives up, and the focus stays where it was.
            if (navigator.landing != landing || !inView(placed[0])) return@LaunchedEffect
            focus.requestFocus()
            if (accessibility?.isTouchExplorationEnabled == true) {
                withFrameNanos { }
                // Only once the row has the input focus, which the screen reader's then follows.
                if (held[0]) moveAccessibilityFocusToFocused(host)
            }
            delay(LANDING)
            // Touched, the row lets the focus go, unless it has moved on meanwhile; driven by a
            // keyboard, it keeps it, to go on from, and so it does while a screen reader explores by
            // touch.
            val exploring = accessibility?.isTouchExplorationEnabled == true
            if (held[0] && inputModes.inputMode == InputMode.Touch && !exploring) focusManager.clearFocus()
        } finally {
            navigator.landed(landing)
        }
    }
    Column(
        modifier =
            Modifier
                .let { if (target.row != null) it.testTag(target.tag) else it }
                .bringIntoViewRequester(relocation)
                // Only while landing: otherwise the row's own focus rules hold.
                .focusProperties { if (landing != null) canFocus = true }
                .focusRequester(focus)
                .onFocusChanged { held[0] = it.hasFocus }
                .onGloballyPositioned { placed[0] = it },
    ) {
        content()
    }
}

/**
 * Whether the row laid out at [coordinates] is in view, whole: its bounds, clipped by its ancestors'
 * (the settings' column among them), as tall as the row.
 */
private fun inView(coordinates: LayoutCoordinates?): Boolean =
    coordinates != null &&
        coordinates.isAttached &&
        coordinates.boundsInRoot().height >= coordinates.size.height - 1

/** How long a landed row may keep the focus it takes in touch mode ([LandingSpot]). */
private val LANDING = 1300.milliseconds

/**
 * Under a service that needs the key [id] (#539): "Set your Anthropic API key here.", the key's name
 * a link that unfolds *Secrets* and lands on the key's row, so the key is always one tap away; once
 * a key is saved ([state]), "Update your Anthropic API key here." (#667), the row there changing it.
 * A key saved but no longer readable is updated too: it is entered again. Nothing before the keys'
 * states are read (a null [state]), which would say "Set your …" for a moment beside a saved key. A
 * row that shows it says what is wrong with the key without naming *Secrets* ([keyNote] with
 * `linked`).
 */
@Composable
internal fun KeyLink(
    id: SecretId,
    state: SecretState?,
) {
    if (state == null) return
    val name = stringResource(keyName(id))
    val sentence = if (keySaved(state)) R.string.settings_update_key else R.string.settings_set_key
    LinkedText(
        sentenceWith(stringResource(sentence, name), settingsLink(name, SettingsTarget.key(id))),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Whether a key in [state] is saved, as the switch of the service that needs it reads it (#667): its
 * switch can be used. A key stored but no longer readable is saved: the service's row says it cannot
 * be read. Not before the keys' states are read (null), nor with no key.
 */
internal fun keySaved(state: SecretState?): Boolean = state == SecretState.SAVED || state == SecretState.UNREADABLE
