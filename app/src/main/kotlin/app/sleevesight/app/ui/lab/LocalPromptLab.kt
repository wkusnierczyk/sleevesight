package app.sleevesight.app.ui.lab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.sleevesight.app.lab.PromptLab

/**
 * The prompt lab (#1003), provided in the debug build only (`MainActivity`, `AppGraph.promptLab`);
 * none in any other, where nothing of it shows.
 */
internal val LocalPromptLab = staticCompositionLocalOf<PromptLab?> { null }

/**
 * Whether the prompt lab is on now (#1388): given, and on ([PromptLab.on]). A credit names the variant
 * that wrote a text ("· prompt B") only while it is, so none shows the moment it is switched off.
 */
@Composable
internal fun labOnNow(): Boolean {
    val lab = LocalPromptLab.current ?: return false
    val on by lab.on.collectAsState()
    return on
}
