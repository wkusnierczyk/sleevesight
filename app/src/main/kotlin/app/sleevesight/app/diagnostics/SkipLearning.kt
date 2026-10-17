package app.sleevesight.app.diagnostics

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * What skip detection has learned, shown on the diagnostics screen with a way to forget it
 * (`design/architecture.md`, *Learning skip detection*, D15). In Phase 1 that is the crossfade
 * learned from natural ends (D5); the learned model per player comes later.
 *
 * Skip detection supplies it (#53); until it does, the screen is given none and says that nothing
 * is learned. The learning keeps its own state: the screen only shows it and asks for the reset.
 */
interface SkipLearning {
    /** The crossfade learned per player, by package name; a player with none learned is absent. */
    val learnedCrossfades: StateFlow<Map<String, Duration>>

    /** Forgets everything learned, so detection falls back to its fixed rules until it learns again. */
    suspend fun reset()
}
