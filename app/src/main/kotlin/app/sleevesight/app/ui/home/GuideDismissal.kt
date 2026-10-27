package app.sleevesight.app.ui.home

import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home guide's *Dismiss once* (#627), held in memory for the process, never stored: [dismissed]
 * from [dismiss] until a track plays ([clearOnPlay]), so the guide is back the next time Home has
 * nothing playing, or when Sleevesight is started again.
 */
class GuideDismissal {
    private val state = MutableStateFlow(false)

    /** Whether the guide is dismissed for now. */
    val dismissed: StateFlow<Boolean> = state.asStateFlow()

    /** Hides the guide until a track plays. */
    fun dismiss() {
        state.value = true
    }

    /** Brings the guide back for the next time nothing plays, on each track [nowPlaying] reports. */
    suspend fun clearOnPlay(nowPlaying: Flow<NowPlaying?>) {
        nowPlaying.collect { if (it != null) state.value = false }
    }
}
