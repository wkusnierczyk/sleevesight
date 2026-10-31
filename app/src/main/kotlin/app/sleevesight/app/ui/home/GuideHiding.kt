package app.sleevesight.app.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home guide's *Hide once* (#627, #759), held in memory, never stored: [hidden] from [hide]
 * until Home is left ([homeLeft], told by [OnHomeLeft]: for another screen, as the settings, or the
 * app going to the background), or a track plays ([clearOnPlay]), so the guide is back the next time Home shows with
 * nothing playing. A *Dismiss*, for good, is stored instead (`Settings.dismissed`).
 */
class GuideHiding {
    private val state = MutableStateFlow(false)

    /** Whether the guide is hidden for now. */
    val hidden: StateFlow<Boolean> = state.asStateFlow()

    /** Hides the guide until Home is left, or a track plays. */
    fun hide() {
        state.value = true
    }

    /** Brings the guide back for when Home is visited again. */
    fun homeLeft() {
        state.value = false
    }

    /** Brings the guide back for the next time nothing plays, on each track [nowPlaying] reports. */
    suspend fun clearOnPlay(nowPlaying: Flow<NowPlaying?>) {
        nowPlaying.collect { if (it != null) state.value = false }
    }
}

/**
 * Tells [left] each time Home is left (#759): its screen stopped, for another screen (the settings,
 * a page) or for the app going to the background, but not for a rotation, which brings Home back as
 * it was. Home's screen is the lifecycle it is drawn in ([LocalLifecycleOwner]): in the app, its
 * entry in the navigation's back stack, which stops once another screen covers it.
 */
@Composable
internal fun OnHomeLeft(left: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val current by rememberUpdatedState(left)
    DisposableEffect(lifecycle, activity) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) current()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
