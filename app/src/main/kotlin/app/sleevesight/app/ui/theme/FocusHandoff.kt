package app.sleevesight.app.ui.theme

import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull

// The focus handed from a control that goes away to the one that takes its place (#623): *Sources*'
// chip on Home giving way to its panel, whose *Close* takes the focus, and back.

/**
 * A move of the focus to a control ([moveTo]) that is laid out only from the next frame, such as the
 * one that replaces the control just tapped. Each control it may move to carries [handoffTarget],
 * which tells it whether the control is in the layout and whether it holds the focus: a move to a
 * control that leaves before the move runs (the panel folded again by a new track) is dropped.
 * Run by [FocusHandoffEffect].
 */
@Stable
internal class FocusHandoff {
    /** The control the focus goes to, until it has it. */
    var pending: FocusRequester? by mutableStateOf(null)
        private set

    // The targets in the layout, and the one holding the focus: read only by the moves, so plain.
    private val attached = mutableSetOf<FocusRequester>()
    private var holding: FocusRequester? = null

    /** Moves the focus to [target] once it is laid out. */
    fun moveTo(target: FocusRequester) {
        pending = target
    }

    /** Whether [target] is in the layout. */
    internal fun isAttached(target: FocusRequester): Boolean = target in attached

    /** Whether [target] holds the input focus. */
    internal fun holds(target: FocusRequester): Boolean = holding === target

    /** The move to [target] has run its course, or [target] has left: no move to it is pending. */
    internal fun done(target: FocusRequester) {
        if (pending === target) pending = null
    }

    internal fun attach(target: FocusRequester) {
        attached += target
    }

    internal fun detach(target: FocusRequester) {
        attached -= target
        if (holding === target) holding = null
        done(target)
    }

    internal fun focused(
        target: FocusRequester,
        focused: Boolean,
    ) {
        if (focused) {
            holding = target
        } else if (holding === target) {
            holding = null
        }
    }
}

/** A [FocusHandoff] kept across recompositions, run by [FocusHandoffEffect]. */
@Composable
internal fun rememberFocusHandoff(): FocusHandoff {
    val handoff = remember { FocusHandoff() }
    FocusHandoffEffect(handoff)
    return handoff
}

/**
 * A control [handoff] may give the focus to, through [requester]. While the focus is on its way to
 * it, it can take the focus even while the phone is touched, which Compose's clickable controls
 * otherwise cannot, so a screen reader exploring by touch can be moved to it; once there, it lets
 * the input focus go again, and the screen reader's stays.
 */
internal fun Modifier.handoffTarget(
    handoff: FocusHandoff,
    requester: FocusRequester,
): Modifier =
    this
        .then(HandoffTargetElement(handoff, requester))
        .onFocusChanged { handoff.focused(requester, it.hasFocus) }
        .focusProperties { if (handoff.pending === requester) canFocus = true }
        .focusRequester(requester)

// Tells [handoff] whether [requester]'s control is in the layout; one that leaves drops a move to it.
private data class HandoffTargetElement(
    val handoff: FocusHandoff,
    val requester: FocusRequester,
) : ModifierNodeElement<HandoffTargetNode>() {
    override fun create() = HandoffTargetNode(handoff, requester)

    override fun update(node: HandoffTargetNode) {
        if (node.isAttached) node.handoff.detach(node.requester)
        node.handoff = handoff
        node.requester = requester
        if (node.isAttached) handoff.attach(requester)
    }
}

private class HandoffTargetNode(
    var handoff: FocusHandoff,
    var requester: FocusRequester,
) : Modifier.Node() {
    override fun onAttach() = handoff.attach(requester)

    override fun onDetach() = handoff.detach(requester)
}

/**
 * Runs [handoff]'s moves, a frame after each is asked for, so its control is laid out: driven by a
 * keyboard, the control takes the focus and keeps it, to go on from; while a screen reader explores
 * by touch (TalkBack), it takes the screen reader's focus, which Compose does not move with the
 * input focus ([moveAccessibilityFocusToFocused]), so the screen reader reads it next; merely
 * touched, nothing moves, as nothing shows the focus there.
 */
@Composable
internal fun FocusHandoffEffect(handoff: FocusHandoff) {
    val target = handoff.pending ?: return
    val host = LocalView.current
    val inputModes = LocalInputModeManager.current
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    LaunchedEffect(target) {
        try {
            withFrameNanos { }
            // Gone before the move could run (folded again by a new track): nothing moves.
            if (!handoff.isAttached(target)) return@LaunchedEffect
            val exploring = accessibility?.isTouchExplorationEnabled == true
            if (!exploring && inputModes.inputMode == InputMode.Touch) return@LaunchedEffect
            target.requestFocus()
            if (exploring) {
                // Only once the control has the input focus, which the screen reader's then follows;
                // never to whatever else holds it.
                withFrameNanos { }
                if (handoff.holds(target)) moveAccessibilityFocusToFocused(host)
            }
        } finally {
            handoff.done(target)
        }
    }
}

/**
 * Gives the screen reader's focus to the node that holds the input focus in [host]'s composition,
 * as a screen reader's own move would ([AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS] on its
 * virtual view, which tells the screen reader with `TYPE_VIEW_ACCESSIBILITY_FOCUSED`). Compose has
 * no API for this, so the node is found in the composition's semantics ([RootForTest], whose
 * `semanticsOwner` is public) by its *focused* property; nothing happens if there is none.
 */
internal fun moveAccessibilityFocusToFocused(host: View) {
    // A walk through Compose's semantics from outside: should it ever fail, the screen reader's focus
    // stays where it was, and the screen goes on.
    runCatching {
        val owner = (host as? RootForTest)?.semanticsOwner ?: return
        val focused =
            owner
                .getAllSemanticsNodes(mergingEnabled = true)
                .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: return
        host.accessibilityNodeProvider
            ?.performAction(focused.id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }
}
