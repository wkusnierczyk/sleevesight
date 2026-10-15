package io.github.wkusnierczyk.sleevesight.app.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has granted [SleevesightListenerService] notification access, and the way to
 * the setting. Android sends no event when the user changes it, so [refresh] is called whenever
 * the app comes back to the foreground.
 */
class NotificationAccess(
    private val context: Context,
) {
    private val listener = ComponentName(context, SleevesightListenerService::class.java)
    private val state = MutableStateFlow(isGranted())

    val granted: StateFlow<Boolean> = state.asStateFlow()

    fun refresh() {
        state.value = isGranted()
    }

    /**
     * The settings screens that grant access, best first: the app's own switch (Android 11 and
     * later), then the list of every app that asks for it. Some devices lack the first, so the
     * caller tries each in turn.
     */
    fun settingsIntents(): List<Intent> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

    private fun isGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}
