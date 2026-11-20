package app.sleevesight.core.settings

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration

/**
 * *Save to a file* and *Load from a file* (#815): the settings as a readable JSON file the user keeps
 * where they choose, and read back.
 *
 * The file holds a [FORMAT] version, the app's version, and under `settings` each setting by its
 * property in [Settings], in the order of [FIELDS]: every setting but those [LEFT_OUT] names, which
 * are no choice to carry to another phone. The developer settings ([DEVELOPER]) are in it only when
 * *Developer mode* is on as it is saved, and are loaded only while it is on. API keys and the
 * history are not settings, so no file ever holds them.
 *
 * Reading never fails on a setting: one this version does not know (from a later one), or a value
 * that is not one of the setting's choices, is skipped and named ([Skipped]), and the others load.
 * A setting the file does not have keeps its value. Only a text that is not such a file at all
 * reads as null ([read]).
 */
@Suppress("TooManyFunctions") // A small reader and writer for each kind of setting.
object SettingsFile {
    /** The file's format; a later version that changes its meaning raises it. */
    const val FORMAT: Int = 1

    /** What the file says it is, under [KIND_KEY]. */
    const val KIND: String = "Sleevesight settings"

    private const val KIND_KEY = "file"
    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "appVersion"
    private const val SETTINGS_KEY = "settings"

    /** *Crossfade*'s *Auto*, in the file. */
    private const val AUTO = "auto"

    /**
     * The settings never in a file, and never loaded from one: *Developer mode* (seven taps on
     * *Version* turn it on), what is dismissed and which descriptions are unfolded (the screens'
     * state, not choices), and the artists' cache lifetime and the cache's size, which have no row.
     */
    val LEFT_OUT: Set<String> =
        setOf("developerMode", "dismissed", "unfoldedDescriptions", "artistCacheLifetime", "cacheSizeLimit")

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
        /** Whether the file holds developer settings. */
        val hasDeveloper: Boolean get() = changes.any { it.field in DEVELOPER }
    }

    /**
     * [current] with what [contents] holds loaded: the developer settings only with [developerMode]
     * (else skipped, as [Skipped.DeveloperModeOff]), and *Keep history for* only where it would not
     * delete history (else [Skipped.ShortensHistory]). The result's [Loaded.skipped] lists all the
     * file's skipped settings.
     */
    fun load(
        contents: Contents,
        current: Settings,
        developerMode: Boolean,
    ): Loaded {
        val skipped = contents.skipped.toMutableList()
        if (!developerMode && contents.hasDeveloper) skipped += Skipped.DeveloperModeOff
        var loaded =
            contents.changes
                .filter { developerMode || it.field !in DEVELOPER }
                .fold(current) { settings, change -> change.apply(settings) }
        if (shortens(current.historyLifetime, loaded.historyLifetime)) {
            skipped += Skipped.ShortensHistory(loaded.historyLifetime)
            loaded = loaded.copy(historyLifetime = current.historyLifetime)
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
     * [settings] as a file, saved by the app's [appVersion]: every setting in [FIELDS], the developer
     * settings only while *Developer mode* is on.
     */
    fun write(
        settings: Settings,
        appVersion: String,
    ): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put(KIND_KEY, KIND)
                put(FORMAT_KEY, FORMAT)
                put(VERSION_KEY, appVersion)
                put(
                    SETTINGS_KEY,
                    JsonObject(
                        FIELDS
                            .filter { settings.developerMode || it.name !in DEVELOPER }
                            .associate { it.name to it.write(settings) },
                    ),
                )
            },
        ) + "\n"

    /**
     * [text] read as a settings file, or null if it is not one (not JSON, or not saying it is a
     * settings file, or without its settings). A setting unknown here, or a value none of its
     * choices, is skipped and named; [LEFT_OUT]'s are passed over.
     */
    fun read(text: String): Contents? {
        val root = settingsFileOf(text) ?: return null
        val values = root.getValue(SETTINGS_KEY) as JsonObject
        val changes = mutableListOf<Change>()
        val skipped = mutableListOf<Skipped>()
        for ((name, value) in values) {
            val field = BY_NAME[name]
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

    /** [text] as a settings file's root: JSON saying it is one, with its settings; else null. */
    private fun settingsFileOf(text: String): JsonObject? {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        val isOne =
            (root?.get(KIND_KEY) as? JsonPrimitive)?.contentOrNull() == KIND && root?.get(SETTINGS_KEY) is JsonObject
        return root.takeIf { isOne }
    }

    /**
     * A setting as a file holds it: [name], its property in [Settings]; [write] its value; [read]
     * the value a file has, as a change of the settings, with what of it is skipped.
     */
    internal class Field(
        val name: String,
        val write: (Settings) -> JsonElement,
        val read: (JsonElement) -> FieldRead,
    )

    /** A setting read from a file: [apply] sets it (null: nothing of it read), [skipped] what was not. */
    internal class FieldRead(
        val apply: ((Settings) -> Settings)?,
        val skipped: List<Skipped> = emptyList(),
    )

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

    /** How a value appears in a skipped line: a text without its quotes, else as in the file. */
    private fun shown(value: JsonElement): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()

    private fun invalid(
        name: String,
        value: JsonElement,
        entry: String? = null,
    ) = Skipped.Invalid(name, entry, shown(value))

    /** A setting of one value, which [parse] reads, null for none of its choices. */
    private fun <T> one(
        name: String,
        of: (Settings) -> T,
        encode: (T) -> JsonElement,
        parse: (JsonElement) -> Parsed<T>?,
        set: Settings.(T) -> Settings,
    ) = Field(
        name,
        write = { encode(of(it)) },
        read = { value ->
            val parsed = parse(value)
            if (parsed == null) {
                FieldRead(null, listOf(invalid(name, value)))
            } else {
                FieldRead({ it.set(parsed.value) })
            }
        },
    )

    /** A value read, null among them: a wrapper, so null can mean none of the choices. */
    private class Parsed<T>(
        val value: T,
    )

    private fun <T> parsed(value: T?): Parsed<T>? = value?.let { Parsed(it) }

    private fun switch(
        name: String,
        of: (Settings) -> Boolean,
        set: Settings.(Boolean) -> Settings,
    ) = one(name, of, ::JsonPrimitive, {
        (it as? JsonPrimitive)
            ?.takeIf { p ->
                !p.isString
            }?.booleanOrNull
            .let(::parsed)
    }, set)

    private inline fun <reified E : Enum<E>> choice(
        name: String,
        noinline of: (Settings) -> E,
        noinline set: Settings.(E) -> Settings,
    ) = one(name, of, { JsonPrimitive(it.name) }, { parsed(enumNamed<E>(it)) }, set)

    private inline fun <reified E : Enum<E>> enumNamed(value: JsonElement): E? {
        val text = (value as? JsonPrimitive)?.contentOrNull() ?: return null
        return enumValues<E>().firstOrNull { it.name == text }
    }

    private fun durationOf(value: JsonElement): Duration? =
        (value as? JsonPrimitive)?.contentOrNull()?.let { Duration.parseOrNull(it) }

    /** A duration, one of [allowed]. */
    private fun duration(
        name: String,
        of: (Settings) -> Duration,
        allowed: (Duration) -> Boolean,
        set: Settings.(Duration) -> Settings,
    ) = one(name, of, { JsonPrimitive(it.toString()) }, { parsed(durationOf(it)?.takeIf(allowed)) }, set)

    /** A set of switches, those on by name: a name unknown here skipped, those not named off. */
    private inline fun <reified E : Enum<E>> switches(
        name: String,
        noinline of: (Settings) -> Set<E>,
        noinline set: Settings.(Set<E>) -> Settings,
    ) = Field(
        name,
        write = { s -> JsonArray(of(s).sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }) },
        read = { value ->
            val items = value as? JsonArray
            if (items == null) {
                FieldRead(null, listOf(invalid(name, value)))
            } else {
                val known = items.mapNotNull { enumNamed<E>(it) }.toSet()
                val unknown = items.filter { enumNamed<E>(it) == null }.map { invalid(name, it, shown(it)) }
                FieldRead({ it.set(known) }, unknown)
            }
        },
    )

    /**
     * A setting made of a value per key, by [key]'s name: each read by [parse] (null for none of its
     * choices), a key unknown here or a value none of its choices skipped; [merge] sets those read.
     */
    @Suppress("LongParameterList") // How its keys and its values are named and read, and how it is set.
    private fun <K, V> perKey(
        name: String,
        of: (Settings) -> Map<K, V>,
        keyName: (K) -> String,
        key: (String) -> K?,
        encode: (V) -> JsonElement,
        parse: (K, JsonElement) -> Parsed<V>?,
        merge: Settings.(Map<K, V>) -> Settings,
    ) = Field(
        name,
        write = { s -> JsonObject(of(s).entries.associate { (k, v) -> keyName(k) to encode(v) }) },
        read = { value ->
            val items = value as? JsonObject
            if (items == null) {
                FieldRead(null, listOf(invalid(name, value)))
            } else {
                val read = mutableMapOf<K, V>()
                val skipped = mutableListOf<Skipped>()
                for ((k, v) in items) {
                    val known = key(k)
                    val parsed = known?.let { parse(it, v) }
                    if (known != null && parsed != null) read[known] = parsed.value else skipped += invalid(name, v, k)
                }
                FieldRead({ it.merge(read) }, skipped)
            }
        },
    )

    private inline fun <reified E : Enum<E>> enumKey(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun text(value: JsonElement): String? = (value as? JsonPrimitive)?.contentOrNull()

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

    /**
     * Every setting a file holds, in the order of [Settings]; with [LEFT_OUT], every property of
     * [Settings], as the codec's test asks.
     */
    @Suppress("LongMethod") // One entry per setting.
    internal val FIELDS: List<Field> =
        listOf(
            choice("layout", { it.layout }) { copy(layout = it) },
            one(
                "aiProvider",
                { it.aiProvider },
                { nullable(it?.name) },
                { v -> if (v is JsonNull) Parsed(null) else enumNamed<ProviderId>(v)?.let { Parsed(it) } },
            ) { copy(aiProvider = it) },
            // A model for each provider chosen off its default; the others at their default.
            Field(
                "aiModels",
                write = { s -> JsonObject(s.aiModels.entries.associate { (k, v) -> k.name to JsonPrimitive(v) }) },
                read = { value ->
                    val items = value as? JsonObject
                    if (items == null) {
                        FieldRead(null, listOf(invalid("aiModels", value)))
                    } else {
                        val read = mutableMapOf<ProviderId, String>()
                        val skipped = mutableListOf<Skipped>()
                        for ((k, v) in items) {
                            val provider = enumKey<ProviderId>(k)
                            val model = text(v)?.takeIf { it.isNotBlank() }
                            if (provider != null &&
                                model != null
                            ) {
                                read[provider] = model
                            } else {
                                skipped += invalid("aiModels", v, k)
                            }
                        }
                        // The file's models in full: a provider it has none for goes to its default.
                        FieldRead({ it.copy(aiModels = read) }, skipped)
                    }
                },
            ),
            perKey(
                "aiEfforts",
                { it.aiEfforts },
                { it.name },
                { enumKey<ProviderId>(it) },
                { nullable(it?.name) },
                { provider, v ->
                    val levels = Settings.EFFORT_SCALES[provider]?.levels
                    when {
                        levels == null -> null
                        v is JsonNull -> Parsed(null)
                        else -> enumNamed<ReasoningEffort>(v)?.takeIf { it in levels }?.let { Parsed(it) }
                    }
                },
            ) { read -> copy(aiEfforts = read) },
            // *Players*: the file's switches over this phone's; a player it does not name keeps its own.
            perKey(
                "followedPlayers",
                { it.followedPlayers },
                { it },
                { it.takeIf { name -> name.isNotBlank() && !Settings.isCall(name) } },
                ::JsonPrimitive,
                { _, v -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { Parsed(it) } },
            ) { read -> copy(followedPlayers = followedPlayers + read) },
            switch("onlyEnabledPlayersOnHome", { it.onlyEnabledPlayersOnHome }) { copy(onlyEnabledPlayersOnHome = it) },
            switch("showPlayerLogo", { it.showPlayerLogo }) { copy(showPlayerLogo = it) },
            choice("symbolOpens", { it.symbolOpens }) { copy(symbolOpens = it) },
            switches<SourceSwitch>("enabledSources", { it.enabledSources }) { copy(enabledSources = it) },
            switches<WebEngine>("webEngines", { it.webEngines }) { copy(webEngines = it) },
            switches<LabelSource>("labelSources", { it.labelSources }) { copy(labelSources = it) },
            switch("showGenres", { it.showGenres }) { copy(showGenres = it) },
            switch("writeInsights", { it.writeInsights }) { copy(writeInsights = it) },
            choice("writeAutomatically", { it.writeAutomatically }) { copy(writeAutomatically = it) },
            one(
                "pauseAutoWriteAfter",
                { it.pauseAutoWriteAfter },
                { it?.let(::JsonPrimitive) ?: JsonNull },
                { v ->
                    if (v is JsonNull) {
                        Parsed(null)
                    } else {
                        (v as? JsonPrimitive)
                            ?.takeIf { !it.isString }
                            ?.intOrNull
                            ?.takeIf { it in Settings.PAUSE_AUTO_WRITE_CHOICES }
                            ?.let { Parsed(it) }
                    }
                },
            ) { copy(pauseAutoWriteAfter = it) },
            one(
                "crossfade",
                { it.crossfade },
                { c -> JsonPrimitive(if (c is Crossfade.Fixed) c.value.toString() else AUTO) },
                { v ->
                    if (text(v) == AUTO) {
                        Parsed(Crossfade.Autodetect)
                    } else {
                        durationOf(
                            v,
                        )?.takeIf { it in Duration.ZERO..Crossfade.Fixed.MAX }?.let { Parsed(Crossfade.Fixed(it)) }
                    }
                },
            ) { copy(crossfade = it) },
            choice("prepareNextTrack", { it.prepareNextTrack }) { copy(prepareNextTrack = it) },
            switch("lookUpInBackground", { it.lookUpInBackground }) { copy(lookUpInBackground = it) },
            switch(
                "artistFactsOncePerSession",
                { it.artistFactsOncePerSession },
            ) { copy(artistFactsOncePerSession = it) },
            one(
                "readAloudVoice",
                { it.readAloudVoice },
                { voice ->
                    voice?.let {
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                        }
                    } ?: JsonNull
                },
                { v ->
                    if (v is JsonNull) {
                        Parsed(null)
                    } else {
                        val voice = v as? JsonObject
                        val id = voice?.get("id")?.let(::text)?.takeIf { it.isNotBlank() }
                        val name = voice?.get("name")?.let(::text)?.takeIf { it.isNotBlank() }
                        id?.let { Parsed(ReadAloudVoice(it, name ?: it)) }
                    }
                },
            ) { copy(readAloudVoice = it) },
            choice("readAloudMusic", { it.readAloudMusic }) { copy(readAloudMusic = it) },
            switch("readTrackLineFirst", { it.readTrackLineFirst }) { copy(readTrackLineFirst = it) },
            switch("readNewInsightsAloud", { it.readNewInsightsAloud }) { copy(readNewInsightsAloud = it) },
            switch("searchWithAi", { it.searchWithAi }) { copy(searchWithAi = it) },
            choice("aiWebSearch", { it.aiWebSearch }) { copy(aiWebSearch = it) },
            choice("trackLanguageSearch", { it.trackLanguageSearch }) { copy(trackLanguageSearch = it) },
            duration("musicBrainzWait", { it.musicBrainzWait }, { it in Settings.MUSICBRAINZ_WAITS }) {
                copy(musicBrainzWait = it)
            },
            duration("aiRetry", { it.aiRetry }, { it in Settings.AI_RETRIES }) { copy(aiRetry = it) },
            // Each source's lifetime the file has; a source it does not name keeps its own.
            perKey(
                "cacheLifetimes",
                { it.cacheLifetimes },
                { it.name },
                { name -> enumKey<SourceId>(name)?.takeIf { it in Settings.DEFAULT_CACHE_LIFETIMES } },
                { JsonPrimitive(it.toString()) },
                { _, v -> durationOf(v)?.takeIf { it.isPositive() }?.let { Parsed(it) } },
            ) { read -> copy(cacheLifetimes = cacheLifetimes + read) },
            switch("keepArtistResults", { it.keepArtistResults }) { copy(keepArtistResults = it) },
            one(
                "historyLifetime",
                { it.historyLifetime },
                { nullable(it?.toString()) },
                { v ->
                    if (v is JsonNull) Parsed(null) else durationOf(v)?.takeIf { it.isPositive() }?.let { Parsed(it) }
                },
            ) { copy(historyLifetime = it) },
            choice("lookUpAfterClear", { it.lookUpAfterClear }) { copy(lookUpAfterClear = it) },
            duration("sessionGap", { it.sessionGap }, { it.isPositive() }) { copy(sessionGap = it) },
            choice("sourcesOnNewTrack", { it.sourcesOnNewTrack }) { copy(sourcesOnNewTrack = it) },
            choice("itemsOnNewTrack", { it.itemsOnNewTrack }) { copy(itemsOnNewTrack = it) },
            switch("onePanel", { it.onePanel }) { copy(onePanel = it) },
            switch("lyricsLinks", { it.lyricsLinks }) { copy(lyricsLinks = it) },
            switch("lyricsSearch", { it.lyricsSearch }) { copy(lyricsSearch = it) },
            switch("showPhoneName", { it.showPhoneName }) { copy(showPhoneName = it) },
            switch("showDiagnostics", { it.showDiagnostics }) { copy(showDiagnostics = it) },
            switch("showGroundingDetails", { it.showGroundingDetails }) { copy(showGroundingDetails = it) },
            choice("reportButton", { it.reportButton }) { copy(reportButton = it) },
            choice("linkStyle", { it.linkStyle }) { copy(linkStyle = it) },
            switch("markDeveloperItems", { it.markDeveloperItems }) { copy(markDeveloperItems = it) },
            switch("foldDescriptions", { it.foldDescriptions }) { copy(foldDescriptions = it) },
            choice("descriptionsOnReturn", { it.descriptionsOnReturn }) { copy(descriptionsOnReturn = it) },
            choice("theme", { it.theme }) { copy(theme = it) },
            switch("themeButton", { it.themeButton }) { copy(themeButton = it) },
            choice("font", { it.font }) { copy(font = it) },
            switch("introAtStart", { it.introAtStart }) { copy(introAtStart = it) },
            switch("spinLogo", { it.spinLogo }) { copy(spinLogo = it) },
            choice("logoSpeed", { it.logoSpeed }) { copy(logoSpeed = it) },
        )

    private val BY_NAME: Map<String, Field> = FIELDS.associateBy { it.name }

    /** The names of the settings a file holds ([FIELDS]), in their order. */
    val NAMES: List<String> = FIELDS.map { it.name }
}
