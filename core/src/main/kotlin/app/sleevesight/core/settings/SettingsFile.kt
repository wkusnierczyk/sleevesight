package app.sleevesight.core.settings

import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * *Save to a file* and *Load from a file* (#815): the settings as a readable JSON file the user keeps
 * where they choose, and read back.
 *
 * The file holds a [FORMAT] version, the app's version, and under `settings` each setting by its
 * property in [Settings], in the order of [NAMES]: every setting but those [LEFT_OUT] names, which
 * are no choice to carry to another phone. The developer settings ([DEVELOPER]) are in it only when
 * *Developer mode* is on as it is saved, and are loaded only while it is on. API keys and the
 * history are not settings, so no file ever holds them.
 *
 * Reading never fails on a setting: one this version does not know (from a later one), or a value
 * that is not one of the setting's choices, is skipped and named ([Skipped]), and the others load.
 * A setting the file does not have keeps its value. Only a text that is not such a file at all
 * reads as null ([read]).
 */
object SettingsFile {
    /** The file's format; a later version that changes its meaning raises it. */
    const val FORMAT: Int = 1

    /** What the file says it is, under [KIND_KEY]. */
    const val KIND: String = "Sleevesight settings"

    private const val KIND_KEY = "file"
    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "appVersion"
    private const val SETTINGS_KEY = "settings"

    /** The most a settings file is, read or written: a few kilobytes are enough. */
    const val MAX_BYTES: Int = 64 * 1024

    /**
     * The most players a file holds, and a load adds: far more than a phone has, and with each at
     * most [MAX_TEXT] characters, a file of them all stays well under [MAX_BYTES].
     */
    const val MAX_PLAYERS: Int = 100

    /** The longest time a file's setting holds: ten years, far beyond what any slider offers. */
    val MAX_TIME: Duration = MAX_TIME_DAYS.days

    private const val MAX_TIME_DAYS = 3650

    /** The longest text a file's setting holds: a player's package name, a model, a voice. */
    const val MAX_TEXT: Int = 255

    /**
     * The settings never in a file, and never loaded from one: *Developer mode* (seven taps on
     * *Version* turn it on), what is dismissed and which descriptions are unfolded (the screens'
     * state, not choices), the artists' cache lifetime and the cache's size, which have no row, and
     * the view (#1447), how this phone shows the settings rather than a setting.
     */
    val LEFT_OUT: Set<String> =
        setOf(
            "developerMode",
            "dismissed",
            "unfoldedDescriptions",
            "artistCacheLifetime",
            "cacheSizeLimit",
            "settingsView",
        )

    /** The developer settings: in a file only from *Developer mode*, loaded only into it. */
    val DEVELOPER: Set<String> =
        setOf("showDiagnostics", "showGroundingDetails", "reportButton", "linkStyle", "markDeveloperItems")

    /** Why a setting in a file was not loaded. */
    sealed interface Skipped {
        /** [name], under `settings`, is no setting this version knows (a later version's). */
        data class Unknown(
            val name: String,
        ) : Skipped

        /**
         * [field]'s [value] (as the file has it) is none of its choices; [entry] names the item of a
         * setting made of several (a source, a player, a provider), null for the whole setting.
         */
        data class Invalid(
            val field: String,
            val entry: String?,
            val value: String,
        ) : Skipped

        /** The developer settings, in the file but loaded only while *Developer mode* is on. */
        data object DeveloperModeOff : Skipped

        /**
         * *Keep history for* at [value] (null: *Forever*), shorter than this phone's, which would
         * delete history: a load never does; it is chosen under *History*, where a shorter time asks.
         */
        data class ShortensHistory(
            val value: Duration?,
        ) : Skipped

        /** [count] items of [field] beyond what a file holds ([MAX_PLAYERS] players), not loaded. */
        data class TooMany(
            val field: String,
            val count: Int,
        ) : Skipped
    }

    /** One setting a file holds, [field] its name, which [apply] sets as the file has it. */
    class Change(
        val field: String,
        val apply: (Settings) -> Settings,
    )

    /**
     * A settings file read: [appVersion] that saved it (null if it does not say), its [format], each
     * setting it holds that this version reads ([changes]), and what it does not ([skipped]).
     */
    class Contents(
        val appVersion: String?,
        val format: Int?,
        val changes: List<Change>,
        val skipped: List<Skipped>,
    ) {
        /** Whether the file holds developer settings, readable or not. */
        val hasDeveloper: Boolean
            get() =
                changes.any { it.field in DEVELOPER } ||
                    skipped.any { it is Skipped.Invalid && it.field in DEVELOPER }

        /** Whether the file sets [field], with a value this version reads. */
        fun sets(field: String): Boolean = changes.any { it.field == field }
    }

    /**
     * What of the settings a file holds and loads where a setting has several items: the AI services
     * ([providers]), the sources' switches ([sourceSwitches]) and the sources whose findings' lifetime
     * is set ([cacheSources]) that the settings show. An item not offered is not written, and in a
     * file is skipped as none of the choices; one not offered keeps its value.
     */
    class Offered(
        val providers: Set<ProviderId> = Settings.DEFAULT_MODELS.keys,
        val sourceSwitches: Set<SourceSwitch> = SourceSwitch.entries.toSet(),
        val cacheSources: Set<SourceId> = Settings.DEFAULT_CACHE_LIFETIMES.keys,
    )

    /**
     * [current] with what [contents] holds loaded: the developer settings only with [developerMode]
     * (else skipped once, as [Skipped.DeveloperModeOff], whatever their values), *Keep history for*
     * only where it would not delete history (else [Skipped.ShortensHistory]), and players only up
     * to [MAX_PLAYERS] in all (new ones beyond, [Skipped.TooMany]). The result's [Loaded.skipped]
     * lists all the file's skipped settings.
     */
    fun load(
        contents: Contents,
        current: Settings,
        developerMode: Boolean,
    ): Loaded {
        val skipped =
            contents.skipped
                .filterNot { !developerMode && it is Skipped.Invalid && it.field in DEVELOPER }
                .toMutableList()
        if (!developerMode && contents.hasDeveloper) skipped += Skipped.DeveloperModeOff
        var loaded =
            contents.changes
                .filter { developerMode || it.field !in DEVELOPER }
                .fold(current) { settings, change -> change.apply(settings) }
        // The voice chosen here, by the file too, keeps the name it was chosen by (#1481).
        if (loaded.readAloudVoice?.id == current.readAloudVoice?.id) {
            loaded = loaded.copy(readAloudVoice = current.readAloudVoice)
        }
        if (shortens(current.historyLifetime, loaded.historyLifetime)) {
            skipped += Skipped.ShortensHistory(loaded.historyLifetime)
            loaded = loaded.copy(historyLifetime = current.historyLifetime)
        }
        val players = loaded.followedPlayers
        if (players.size > maxOf(MAX_PLAYERS, current.followedPlayers.size)) {
            // The new players beyond the bound, unfollowed first, are not added.
            val room = maxOf(MAX_PLAYERS - current.followedPlayers.size, 0)
            val added =
                (players - current.followedPlayers.keys)
                    .entries
                    .sortedWith(compareBy({ !it.value }, { it.key }))
            val kept = added.take(room).associate { it.key to it.value }
            skipped += Skipped.TooMany("followedPlayers", added.size - kept.size)
            loaded = loaded.copy(followedPlayers = players.filterKeys { it in current.followedPlayers } + kept)
        }
        return Loaded(loaded, skipped)
    }

    /** The settings a load leaves ([settings]), and what of the file it did not load ([skipped]). */
    data class Loaded(
        val settings: Settings,
        val skipped: List<Skipped>,
    )

    /** Whether *Keep history for* going from [from] to [to] would delete history; null is *Forever*. */
    private fun shortens(
        from: Duration?,
        to: Duration?,
    ): Boolean = to != null && (from == null || to < from)

    private val json = Json { prettyPrint = true }

    /**
     * [settings] as a file, saved by the app's [appVersion]: every setting in [NAMES], what has
     * several items only as [offered], the developer settings only while *Developer mode* is on.
     * Fails ([IllegalStateException]) rather than write more than [MAX_BYTES], which no load reads.
     */
    fun write(
        settings: Settings,
        appVersion: String,
        offered: Offered = Offered(),
    ): String {
        val text =
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(KIND_KEY, KIND)
                    put(FORMAT_KEY, FORMAT)
                    put(VERSION_KEY, appVersion)
                    put(
                        SETTINGS_KEY,
                        JsonObject(
                            settingsFileFields(offered)
                                .filter { settings.developerMode || it.name !in DEVELOPER }
                                .associate { it.name to it.write(settings) },
                        ),
                    )
                },
            ) + "\n"
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Settings file larger than $MAX_BYTES bytes" }
        return text
    }

    /**
     * [text] read as a settings file, or null if it is not one (not JSON, or not saying it is a
     * settings file, or without its settings). A setting unknown here, or a value none of its
     * choices ([offered] for what has several items), is skipped and named; [LEFT_OUT]'s are passed
     * over.
     */
    fun read(
        text: String,
        offered: Offered = Offered(),
    ): Contents? {
        val root = settingsFileOf(text) ?: return null
        val values = root.getValue(SETTINGS_KEY) as JsonObject
        val fields = settingsFileFields(offered).associateBy { it.name }
        val changes = mutableListOf<Change>()
        val skipped = mutableListOf<Skipped>()
        for ((name, value) in values) {
            val field = fields[name]
            when {
                field != null -> {
                    val read = field.read(value)
                    read.apply?.let { changes += Change(name, it) }
                    skipped += read.skipped
                }
                name !in LEFT_OUT -> skipped += Skipped.Unknown(name)
            }
        }
        return Contents(
            appVersion = (root[VERSION_KEY] as? JsonPrimitive)?.contentOrNull(),
            format = (root[FORMAT_KEY] as? JsonPrimitive)?.intOrNull,
            changes = changes,
            skipped = skipped,
        )
    }

    /** How deep a settings file's values go at most: its settings are in objects three deep. */
    private const val MAX_NESTING = 16

    /**
     * How deep [text]'s arrays and objects go, outside its strings: read before parsing it, whose
     * parser recurses, so a file nested thousands deep cannot overflow the stack.
     */
    private fun nesting(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' || c == '{' -> deepest = maxOf(deepest, ++depth)
                c == ']' || c == '}' -> depth--
            }
        }
        return deepest
    }

    /** [text] as a settings file's root: JSON saying it is one, with its settings; else null. */
    private fun settingsFileOf(text: String): JsonObject? {
        if (nesting(text) > MAX_NESTING) return null
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val isOne =
            (root?.get(KIND_KEY) as? JsonPrimitive)?.contentOrNull() == KIND && root[SETTINGS_KEY] is JsonObject
        return root.takeIf { isOne }
    }

    /** The names of the settings a file holds, in their order. */
    val NAMES: List<String> = settingsFileFields(Offered()).map { it.name }

    /**
     * The settings [to] has off [from], each by its name with its value in [to] as a file has it (JSON): what
     * a load changes, to tell whether the load saved is the one previewed.
     */
    fun changes(
        from: Settings,
        to: Settings,
    ): Map<String, String> =
        settingsFileFields(Offered())
            .mapNotNull { field ->
                field.write(to).takeIf { it != field.write(from) }?.let { field.name to it.toString() }
            }.toMap()
}
