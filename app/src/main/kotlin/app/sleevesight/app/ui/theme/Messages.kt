package app.sleevesight.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * Short messages at the bottom of a screen (#759), Material's snackbars: what a tap did, with an
 * action that undoes it where there is one. One at a time: a new message replaces the one showing.
 * Shown in [scope], the screen's, so a message outlives the part of the screen that asked for it,
 * as a panel just dismissed, but not the screen.
 */
class Messages(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /**
     * Shows [message], with [action], when given, a tap on which runs [onAction]: shown longer then,
     * so there is time to reach it.
     */
    fun show(
        message: String,
        action: String? = null,
        onAction: () -> Unit = {},
    ) {
        host.currentSnackbarData?.dismiss()
        scope.launch {
            val duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long
            val result = host.showSnackbar(message, actionLabel = action, duration = duration)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /**
     * Shows [message] as [show] does once [saved] says the change it is about was saved; else
     * [failed], when given, or nothing.
     */
    fun showOnceSaved(
        saved: Deferred<Boolean>,
        message: String,
        action: String? = null,
        failed: String? = null,
        onAction: () -> Unit = {},
    ) {
        scope.launch {
            when {
                saved.await() -> show(message, action, onAction)
                failed != null -> show(failed)
            }
        }
    }

    /** Shows [failed] if [saved] says the change it is about was not saved; else nothing. */
    fun showIfFailed(
        saved: Deferred<Boolean>,
        failed: String,
    ) {
        scope.launch { if (!saved.await()) show(failed) }
    }
}

/** The screen's [Messages] ([MessageHost]); null outside one, as in a test of a part alone. */
val LocalMessages = staticCompositionLocalOf<Messages?> { null }

/**
 * A screen's [content], with its [Messages] ([LocalMessages]) shown over it at the bottom (#759),
 * the space [modifier] gives.
 */
@Composable
fun MessageHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messages = remember(host, scope) { Messages(host, scope) }
    CompositionLocalProvider(LocalMessages provides messages) {
        Box(modifier) {
            content()
            SnackbarHost(host, Modifier.align(Alignment.BottomCenter).testTag(MESSAGE_TAG))
        }
    }
}

/** The test tag of the message shown at the bottom of a screen ([MessageHost]). */
const val MESSAGE_TAG: String = "message"
