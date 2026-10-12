package io.github.wkusnierczyk.sleevesight.spike

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** How the probe was installed and whether it has notification access: the D7 restricted-settings check. */
object Install {
    fun hasNotificationAccess(context: Context): Boolean {
        val component = ComponentName(context, ProbeListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it == component }
    }

    @Suppress("DEPRECATION") // getInstallerPackageName is the only call before API 30.
    fun installer(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }

    fun describe(context: Context): JSONObject =
        JSONObject()
            .put("installer", installer(context) ?: "none (adb or unknown)")
            .put("notificationAccess", hasNotificationAccess(context))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
}
