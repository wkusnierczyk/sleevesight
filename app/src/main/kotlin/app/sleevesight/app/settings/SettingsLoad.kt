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
import java.util.concurrent.atomic.AtomicReference

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
 * What a load says once saved (#815): [previewed], the message the preview made, while the load
 * saved is the one previewed, [expected] the settings it changes ([SettingsFile.changes]) and
 * [expectedSkipped] how many items it did not load; else, as when a save waiting its turn ahead of it
 * changed what it changes, [counted] from what it saved. With no [expected], always [previewed].
 */
class LoadMessage(
    val previewed: String,
    val expected: Map<String, String>? = null,
    val expectedSkipped: Int = 0,
    val counted: (loaded: Int, skipped: Int) -> String = { _, _ -> previewed },
) {
    /** The message for a load that saved [changes] and did not load [skipped] items. */
    fun said(
        changes: Map<String, String>,
        skipped: Int,
    ): String =
        if (expected == null || (changes == expected && skipped == expectedSkipped)) {
            previewed
        } else {
            counted(changes.size, skipped)
        }
}

/**
 * [contents] loaded into [current] ([SettingsFile.load]), each service it would switch on whose key
 * is [missing] left off, as the keys' gate would switch it off ([keylessOff]). A service on already
 * stays as it is: the gate's to keep. A web search engine's switch is loaded as its row in the
 * settings shows it, on while web search is on with it ([webAsShown]).
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
    return LoadPlan(gated.webAsShown(current), loaded.skipped, keyless)
}

/**
 * These settings, loaded over [current], with web search and its engines changed only as the
 * settings' rows show them (#815): an engine's row is on while web search is on with it. A row the
 * load leaves as it was keeps web search and the engine as they were, so a load changes nothing it
 * would not list; a row switched on turns both on, and one switched off the engine, as the row does.
 */
internal fun Settings.webAsShown(current: Settings): Settings {
    fun on(
        settings: Settings,
        engine: WebEngine,
    ) = SourceSwitch.WEB in settings.enabledSources && engine in settings.webEngines
    var sources = current.enabledSources.let { if (SourceSwitch.WEB in it) setOf(SourceSwitch.WEB) else emptySet() }
    var engines = current.webEngines
    for (engine in WebEngine.entries) {
        val now = on(current, engine)
        when {
            on(this, engine) == now -> Unit
            !now -> {
                sources = sources + SourceSwitch.WEB
                engines = engines + engine
            }
            else -> engines = engines - engine
        }
    }
    return copy(enabledSources = enabledSources - SourceSwitch.WEB + sources, webEngines = engines)
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
): (shown: Settings, contents: SettingsFile.Contents, message: LoadMessage) -> Deferred<Boolean> =
    { shown, contents, message ->
        val mark = historyLifetimeAsked.mark()
        val mode = SettingsFile.load(contents, shown, shown.developerMode).settings.writeAutomatically
        val settled = if (contents.sets("writeAutomatically")) chosen(mode) else null
        // The preview's message until the save says otherwise: what it saved, if not what was shown.
        val said = AtomicReference(message.previewed)
        saving(saves) {
            loadFile(
                contents,
                missing(),
                mark,
                settled,
            ) { changes, skipped -> said.set(message.said(changes, skipped)) }
        }.also { resets.saving(it) { said.get() } }
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
    saved: (changes: Map<String, String>, skipped: Int) -> Unit = { _, _ -> },
) {
    try {
        var changedHistory = false
        var changes = emptyMap<String, String>()
        var skipped = 0
        update { stored ->
            val plan = loadPlan(contents, stored, missing)
            changes = SettingsFile.changes(stored, plan.settings)
            skipped = plan.skipped.size + plan.keyless.size
            plan.settings.also { changedHistory = it.historyLifetime != stored.historyLifetime }
        }
        saved(changes, skipped)
        // As after a reset: *Keep history for* is judged against what the load stored.
        if (changedHistory) historyLifetimeAsked.forget(mark)
    } finally {
        settled?.invoke(storedMode())
    }
}
