package app.sleevesight.app.settings

import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.keylessOff
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * A service a settings file switches on (#815) that is not, its key not saved on this phone: the AI
 * service ([Provider]), a source ([Source]) or a web search engine ([Engine]). The keys' gate keeps
 * such a service off ([keylessOff], #667), so a load leaves it off and says why.
 */
sealed interface Keyless {
    data class Provider(
        val provider: ProviderId,
    ) : Keyless

    data class Source(
        val switch: SourceSwitch,
    ) : Keyless

    data class Engine(
        val engine: WebEngine,
    ) : Keyless
}

/**
 * What loading a file leaves: [settings], what of the file is not loaded ([skipped]), and the
 * services it switches on that stay off for want of their key ([keyless]). One plan for the preview
 * and the load, so the preview promises only what the load does.
 */
class LoadPlan(
    val settings: Settings,
    val skipped: List<SettingsFile.Skipped>,
    val keyless: List<Keyless>,
)

/**
 * [contents] loaded into [current] ([SettingsFile.load]), each service it would switch on whose key
 * is [missing] left off, as the keys' gate would switch it off ([keylessOff]). A service on already
 * stays as it is: the gate's to keep.
 */
fun loadPlan(
    contents: SettingsFile.Contents,
    current: Settings,
    missing: Set<SecretId>,
): LoadPlan {
    val loaded = SettingsFile.load(contents, current, current.developerMode)
    val to = loaded.settings

    fun lacks(id: SecretId?) = id != null && id in missing
    val provider = to.aiProvider?.takeIf { it != current.aiProvider && lacks(SecretId.forProvider(it)) }
    val sources = (to.enabledSources - current.enabledSources).filter { lacks(SecretId.forSource(it)) }
    val engines = (to.webEngines - current.webEngines).filter { lacks(SecretId.forEngine(it)) }
    val gated =
        to.copy(
            aiProvider = if (provider != null) current.aiProvider else to.aiProvider,
            enabledSources = to.enabledSources - sources.toSet(),
            webEngines = to.webEngines - engines.toSet(),
        )
    val keyless =
        listOfNotNull(provider?.let { Keyless.Provider(it) }) +
            sources.map { Keyless.Source(it) } +
            engines.map { Keyless.Engine(it) }
    return LoadPlan(gated, loaded.skipped, keyless)
}

/**
 * *Load from a file* (#815) on this store: each call, given the settings shown when *Load* was tapped,
 * the file's [SettingsFile.Contents] and the message to say once it is saved, saves the load in
 * [saves], which outlives the screen, in line with the other saves ([saving]); its result whether it
 * was saved. The load is made on the settings stored when its turn comes, so a setting the file does
 * not have keeps the value it has then ([loadPlan]: the developer settings only while *Developer
 * mode* is on, *Keep history for* never shorter, a service whose key is [missing] then left off). The
 * message is said as a reset's is ([SettingsStore.resets]), so a load that changes the theme, which
 * restarts the activity, still says it.
 *
 * A file that sets *Reactive*'s mode is a choice of the mode, told to [chosen] at the call, as a
 * reset's is ([resetter]), whether or not it differs from the one shown: a choice still waiting its
 * turn may differ from both, and the load, after it, decides.
 */
fun SettingsStore.loader(
    saves: CoroutineScope,
    missing: suspend () -> Set<SecretId> = { emptySet() },
    chosen: (WriteAutomatically) -> (WriteAutomatically?) -> Unit = { {} },
): (shown: Settings, contents: SettingsFile.Contents, message: String) -> Deferred<Boolean> =
    { shown, contents, message ->
        val mark = historyLifetimeAsked.mark()
        val mode = SettingsFile.load(contents, shown, shown.developerMode).settings.writeAutomatically
        val settled = if (contents.sets("writeAutomatically")) chosen(mode) else null
        saving(saves) { loadFile(contents, missing(), mark, settled) }.also { resets.saving(it, message) }
    }

/**
 * What [contents] holds, loaded into the stored settings (#815), a service whose key is [missing]
 * left off. Takes no turn, as [SettingsStore.update]: [loader] takes it. It tells [settled], if any,
 * what the store holds for *Reactive* once the save has ended, saved or not.
 */
internal suspend fun SettingsStore.loadFile(
    contents: SettingsFile.Contents,
    missing: Set<SecretId> = emptySet(),
    mark: HistoryLifetimeAsked.Mark = historyLifetimeAsked.mark(),
    settled: ((WriteAutomatically?) -> Unit)? = null,
) {
    try {
        var changedHistory = false
        update { stored ->
            loadPlan(contents, stored, missing).settings.also {
                changedHistory = it.historyLifetime != stored.historyLifetime
            }
        }
        // As after a reset: *Keep history for* is judged against what the load stored.
        if (changedHistory) historyLifetimeAsked.forget(mark)
    } finally {
        settled?.invoke(storedMode())
    }
}
