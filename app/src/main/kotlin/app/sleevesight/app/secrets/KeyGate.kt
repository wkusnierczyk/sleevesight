package app.sleevesight.app.secrets

import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.IOException

/**
 * [Settings] with every service whose key is [missing] switched off (#667): the AI service
 * ([Settings.aiProvider], [SecretId.forProvider]), a source ([SecretId.forSource]) and a web search
 * engine ([SecretId.forEngine]). Nothing is switched on: a service whose key is saved keeps its
 * switch as it was. The same settings when nothing changes.
 */
internal fun Settings.keylessOff(missing: Set<SecretId>): Settings {
    fun lacks(id: SecretId?) = id != null && id in missing
    val provider = aiProvider?.takeUnless { lacks(SecretId.forProvider(it)) }
    val sources = enabledSources.filterNot { lacks(SecretId.forSource(it)) }.toSet()
    val engines = webEngines.filterNot { lacks(SecretId.forEngine(it)) }.toSet()
    return if (provider == aiProvider && sources == enabledSources && engines == webEngines) {
        this
    } else {
        copy(aiProvider = provider, enabledSources = sources, webEngines = engines)
    }
}

/**
 * The keys with no entry stored (#667), which the services that need them are kept off for. A key
 * stored but not readable is stored, and while the keys' file cannot be read every key is, as
 * [SecretStore.isStored] has it: nothing then says which are gone, so none is missing.
 */
internal suspend fun SecretStore.missing(): Set<SecretId> = SecretId.entries.filterNot { isStored(it) }.toSet()

/**
 * Keeps a service that needs a key off, in the stored settings, while its key is not saved (#667):
 * whenever the keys change (one saved, removed, or every one purged) or the settings do, each
 * service switched on whose key has no entry is switched off and saved so ([keylessOff]). Settings
 * from an earlier version, which let a service be on with no key, come up off at the first start
 * with this; a key removed switches its service off; a key saved switches nothing on, so its
 * service stays off until it is switched on.
 *
 * A rule on the stored settings, not one applied as they are read: read-time, the stored switch
 * would stay on, and saving the key would switch the service back on by itself. Not a DataStore
 * migration of the settings either: the keys are in a file of their own ([SecretStore]), and a
 * migration runs once, where a key is removed at any time.
 *
 * A key stored but not readable ([SecretState.UNREADABLE]) counts as saved here: its service keeps
 * its switch, and says the key can no longer be read until it is entered again. While the keys'
 * file cannot be read nothing is switched off ([missing] is empty), nor while the settings' cannot
 * be written; the next change of either tries again.
 *
 * @param read the settings as they change: the app's are read only once the first run is
 *   acknowledged.
 */
class KeyGate(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val read: Flow<Settings> = settings.settings,
) {
    /** Runs until cancelled. */
    suspend fun run() {
        combine(secrets.changes, read) { _, now -> now }.collect { now ->
            val missing = secrets.missing()
            if (now.keylessOff(missing) == now) return@collect
            try {
                // The keys as read just before; the settings as stored at the write.
                settings.update { it.keylessOff(missing) }
            } catch (_: IOException) {
                // Not written: the switch stays on until the next change of the keys or settings.
            }
        }
    }
}
