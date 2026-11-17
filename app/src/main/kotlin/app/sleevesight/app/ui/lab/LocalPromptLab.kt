package app.sleevesight.app.ui.lab

import androidx.compose.runtime.staticCompositionLocalOf
import app.sleevesight.app.lab.PromptLab

/**
 * The prompt lab (#1003), provided in the debug build only (`MainActivity`, `AppGraph.promptLab`);
 * none in any other, where nothing of it shows.
 */
internal val LocalPromptLab = staticCompositionLocalOf<PromptLab?> { null }
