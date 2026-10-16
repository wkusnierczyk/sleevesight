package app.sleevesight.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.sleevesight.app.secrets.AndroidKeystoreKey
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.session.NotificationAccess
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

/**
 * The app's objects, wired by hand in one place (architecture, *Technology choices*): one graph
 * per process, held by [SleevesightApplication]. Components take what they need through their
 * constructors; nothing outside this class constructs a long-lived object.
 */
class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val notificationAccess = NotificationAccess(appContext)

    val firstRun = FirstRunAcknowledgement(appContext)

    /** The user's settings. DataStore allows one instance per file in a process, so only here. */
    val settings = SettingsStore(preferences(appContext, SETTINGS_FILE))

    /** The user's keys, sealed with the app's Android Keystore key, in a file of their own. */
    val secrets = SecretStore(preferences(appContext, SECRETS_FILE), SecretCipher(AndroidKeystoreKey()))

    /**
     * The AI services' clients, for the settings screen's model list. Empty until the pipeline is
     * wired on the phone (#51), which creates the process's one `NetClient` they need; until then the
     * screen offers each service's default model.
     */
    val aiProviders: Map<ProviderId, AiProvider> = emptyMap()

    /** A player's name as the phone knows it, else as the design knows it, else its package. */
    fun playerLabel(packageName: String): String {
        val pm = appContext.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // Not installed, or not visible to this app (package visibility, Android 11 and later).
            KNOWN_PLAYERS[packageName] ?: packageName
        }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
        const val SECRETS_FILE = "secrets"

        val KNOWN_PLAYERS = mapOf(Settings.SPOTIFY to "Spotify")

        /**
         * A Preferences DataStore in the app's own files (`files/datastore/`), which the backup and
         * transfer rules exclude with everything else. A file DataStore cannot parse is replaced by
         * an empty one: the settings return to their defaults, the keys read as not set.
         */
        fun preferences(
            context: Context,
            name: String,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile(name) },
            )
    }
}
