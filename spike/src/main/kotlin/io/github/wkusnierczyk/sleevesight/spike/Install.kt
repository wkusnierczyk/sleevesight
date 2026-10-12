package io.github.wkusnierczyk.sleevesight.spike

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/** How the probe was installed and whether it has notification access: the D7 restricted-settings check. */
object Install {
    // The constants are API 33 compile-time ints, read only under the TIRAMISU guard below.
    @SuppressLint("InlinedApi")
    private val SOURCES =
        mapOf(
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED to "UNSPECIFIED",
            PackageInstaller.PACKAGE_SOURCE_OTHER to "OTHER",
            PackageInstaller.PACKAGE_SOURCE_STORE to "STORE",
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE to "LOCAL_FILE",
            PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE to "DOWNLOADED_FILE",
        )

    fun hasNotificationAccess(context: Context): Boolean {
        val component = ComponentName(context, ProbeListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure
                .getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty()
                .split(':')
                .any { it == component.flattenToString() }
        }
    }

    @Suppress("DEPRECATION") // getInstallerPackageName is the only call before API 30.
    fun describe(context: Context): JSONObject {
        val json =
            JSONObject()
                .put("notificationAccess", hasNotificationAccess(context))
                .put("sdk", Build.VERSION.SDK_INT)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = context.packageManager.getInstallSourceInfo(context.packageName)
            json
                .put("installer", info.installingPackageName ?: "none (adb or unknown)")
                .put("initiatingPackage", info.initiatingPackageName)
                // Null unless the caller holds INSTALL_PACKAGES, which the probe cannot: not a D7 result.
                .put("originatingPackage", info.originatingPackageName ?: "unavailable without INSTALL_PACKAGES")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                json.put("packageSource", SOURCES[info.packageSource] ?: "UNKNOWN(${info.packageSource})")
            }
        } else {
            json.put(
                "installer",
                context.packageManager.getInstallerPackageName(context.packageName) ?: "none (adb or unknown)",
            )
        }
        return json
    }
}
