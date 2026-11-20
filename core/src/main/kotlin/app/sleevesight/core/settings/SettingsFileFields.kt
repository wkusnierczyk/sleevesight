@file:Suppress("TooManyFunctions") // A small reader and writer for each kind of setting.

package app.sleevesight.core.settings

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.SettingsFile.Skipped
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// Each setting of a settings file (#815, [SettingsFile]): how it is written, and how a file's value
// is read back, or skipped and named.

/**
 * A setting as a file holds it: [name], its property in [Settings]; [write] its value; [read] the
 * value a file has, as a change of the settings, with what of it is skipped.
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

/** A value read, null among them: a wrapper, so null can mean none of the choices. */
private class Parsed<T>(
    val value: T,
)

private fun <T> parsed(value: T?): Parsed<T>? = value?.let { Parsed(it) }

/** *Crossfade*'s *Auto*, in the file. */
private const val AUTO = "auto"

internal fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

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
        if (parsed == null) FieldRead(null, listOf(invalid(name, value))) else FieldRead({ it.set(parsed.value) })
    },
)

private fun boolOf(value: JsonElement): Boolean? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun switch(
    name: String,
    of: (Settings) -> Boolean,
    set: Settings.(Boolean) -> Settings,
) = one(name, of, ::JsonPrimitive, { parsed(boolOf(it)) }, set)

// No inline functions with type parameters known at run time here: the lambdas of such a
// function's own body are classes too, which ART initializes (`ClassInitializationTest`), and one
// that calls such a function throws there.

/** A choice, one of [entries] by its name. */
private fun <E : Enum<E>> choice(
    name: String,
    entries: List<E>,
    of: (Settings) -> E,
    set: Settings.(E) -> Settings,
) = one(name, of, { JsonPrimitive(it.name) }, { parsed(enumNamed(it, entries)) }, set)

/** The one of [entries] that [value] names, or null. */
private fun <E : Enum<E>> enumNamed(
    value: JsonElement,
    entries: Collection<E>,
): E? {
    val text = (value as? JsonPrimitive)?.contentOrNull() ?: return null
    return enumKey(text, entries)
}

/** The one of [entries] named [name], or null. */
private fun <E : Enum<E>> enumKey(
    name: String,
    entries: Collection<E>,
): E? = entries.firstOrNull { it.name == name }

private fun text(value: JsonElement): String? = (value as? JsonPrimitive)?.contentOrNull()

private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

/**
 * A duration as a file has it (`90d`, `15s`), only where it is finite, at most
 * [SettingsFile.MAX_TIME], and a whole number of [unit]: the store keeps milliseconds, and a
 * slider's stops are whole days or minutes, so anything finer would not read back as written, or
 * not show as it is.
 */
private fun durationOf(
    value: JsonElement,
    unit: Duration = 1.milliseconds,
): Duration? =
    text(value)
        ?.let { Duration.parseOrNull(it) }
        ?.takeIf {
            it.isFinite() &&
                it <= SettingsFile.MAX_TIME &&
                it == it.inWholeMilliseconds.milliseconds &&
                it.inWholeMilliseconds % unit.inWholeMilliseconds == 0L
        }

/** A text as a file has it, not blank and at most [SettingsFile.MAX_TEXT] characters: a model, a voice. */
private fun shortText(value: JsonElement): String? =
    text(value)?.takeIf { it.isNotBlank() && it.length <= SettingsFile.MAX_TEXT }

/** Whether [name] can be a player's: a package name, at most [SettingsFile.MAX_TEXT] characters. */
internal fun isPlayerName(name: String): Boolean =
    name.length <= SettingsFile.MAX_TEXT && PACKAGE_NAME.matches(name) && !Settings.isCall(name)

/** An Android package name's characters. */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

/** A duration, one of [allowed]. */
private fun duration(
    name: String,
    of: (Settings) -> Duration,
    allowed: (Duration) -> Boolean,
    unit: Duration = 1.milliseconds,
    set: Settings.(Duration) -> Settings,
) = one(name, of, { JsonPrimitive(it.toString()) }, { parsed(durationOf(it, unit)?.takeIf(allowed)) }, set)

/**
 * A set of switches, those on by name, of which [offered] have a switch in the settings: a name
 * unknown here or not offered is skipped, an offered one not named is off, and one not offered keeps
 * its value.
 */
private fun <E : Enum<E>> switches(
    name: String,
    offered: Set<E>,
    of: (Settings) -> Set<E>,
    set: Settings.(Set<E>) -> Settings,
) = Field(
    name,
    write = { s -> JsonArray(of(s).filter { it in offered }.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }) },
    read = { value ->
        val items = value as? JsonArray
        if (items == null) {
            FieldRead(null, listOf(invalid(name, value)))
        } else {
            val known = items.mapNotNull { enumNamed(it, offered)?.takeIf { e -> e in offered } }.toSet()
            val skipped =
                items
                    .filter {
                        enumNamed(it, offered)?.takeIf { e ->
                            e in offered
                        } == null
                    }.map { invalid(name, it, shown(it)) }
            FieldRead({ it.set(of(it) - offered + known) }, skipped)
        }
    },
)

/**
 * A setting made of a value per key: [parse] reads an entry, null for a key not offered or a value
 * none of its choices, which is skipped and named; [apply] sets each entry read, so a key the file
 * does not name, or names wrongly, keeps its value.
 */
private fun <V> perKey(
    name: String,
    write: (Settings) -> Map<String, JsonElement>,
    parse: (String, JsonElement) -> Parsed<V>?,
    apply: Settings.(String, V) -> Settings,
) = Field(
    name,
    write = { s -> JsonObject(write(s)) },
    read = { value ->
        val items = value as? JsonObject
        if (items == null) {
            FieldRead(null, listOf(invalid(name, value)))
        } else {
            val read = mutableListOf<Pair<String, V>>()
            val skipped = mutableListOf<Skipped>()
            for ((k, v) in items) {
                val parsed = parse(k, v)
                if (parsed != null) read += k to parsed.value else skipped += invalid(name, v, k)
            }
            FieldRead({ settings -> read.fold(settings) { s, (k, v) -> s.apply(k, v) } }, skipped)
        }
    },
)

/**
 * *Players*: a switch per player, its package name the key; one listed by default, or seen here,
 * that the file does not name keeps its switch. A file holds at most [SettingsFile.MAX_PLAYERS],
 * the followed first, so it always loads back; a load adds no more than that ([SettingsFile.load]).
 */
private fun players(): Field =
    Field(
        "followedPlayers",
        write = { s ->
            val kept =
                s.followedPlayers.entries
                    .filter { isPlayerName(it.key) }
                    .sortedWith(compareBy({ !it.value }, { it.key }))
                    .take(SettingsFile.MAX_PLAYERS)
            JsonObject(kept.associate { (k, v) -> k to JsonPrimitive(v) })
        },
        read = { value ->
            val items = value as? JsonObject
            if (items == null) {
                FieldRead(null, listOf(invalid("followedPlayers", value)))
            } else {
                val skipped = mutableListOf<Skipped>()
                val read = mutableMapOf<String, Boolean>()
                for ((k, v) in items) {
                    val on = boolOf(v)
                    when {
                        Settings.isCall(k) -> Unit // A phone call is never a player (#435).
                        !isPlayerName(k) || on == null -> skipped += invalid("followedPlayers", v, k)
                        else -> read[k] = on
                    }
                }
                FieldRead({ it.copy(followedPlayers = it.followedPlayers + read) }, skipped)
            }
        },
    )

/**
 * Every setting a file holds, in the order of [Settings], the maps and sets only with what
 * [offered] names; with [SettingsFile.LEFT_OUT], every property of [Settings].
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One entry per setting.
internal fun settingsFileFields(offered: SettingsFile.Offered): List<Field> {
    val providers = offered.providers
    return listOf(
        choice("layout", Layout.entries, { it.layout }) { copy(layout = it) },
        one(
            "aiProvider",
            { it.aiProvider?.takeIf { p -> p in providers } },
            { nullable(it?.name) },
            { v ->
                if (v is JsonNull) {
                    Parsed(
                        null,
                    )
                } else {
                    enumNamed(v, ProviderId.entries)?.takeIf { it in providers }?.let { Parsed(it) }
                }
            },
        ) { copy(aiProvider = it) },
        // Each provider's model, its default included, so a default in the file replaces a choice here.
        perKey(
            "aiModels",
            { s ->
                providers.filter { it in Settings.DEFAULT_MODELS }.associate { p ->
                    p.name to nullable(s.model(p))
                }
            },
            { k, v ->
                val provider =
                    enumKey(
                        k,
                        ProviderId.entries,
                    )?.takeIf { it in providers && it in Settings.DEFAULT_MODELS }
                val model = shortText(v)
                if (provider != null && model != null) Parsed(provider to model) else null
            },
        ) { _, (provider, model) ->
            if (model ==
                Settings.DEFAULT_MODELS[provider]
            ) {
                copy(aiModels = aiModels - provider)
            } else {
                withModel(provider, model)
            }
        },
        // Each provider's effort, its default included; null is the provider's own default.
        perKey(
            "aiEfforts",
            { s ->
                providers.filter { it in Settings.EFFORT_SCALES }.associate { p ->
                    p.name to
                        nullable(s.effort(p)?.name)
                }
            },
            { k, v ->
                val provider = enumKey(k, ProviderId.entries)?.takeIf { it in providers }
                val levels = provider?.let { Settings.EFFORT_SCALES[it]?.levels }
                when {
                    levels == null -> null
                    v is JsonNull -> Parsed(provider to null)
                    else ->
                        enumNamed(
                            v,
                            ReasoningEffort.entries,
                        )?.takeIf { it in levels }?.let { Parsed(provider to it) }
                }
            },
        ) { _, (provider, effort) -> withEffort(provider, effort) },
        players(),
        switch("onlyEnabledPlayersOnHome", { it.onlyEnabledPlayersOnHome }) { copy(onlyEnabledPlayersOnHome = it) },
        switch("showPlayerLogo", { it.showPlayerLogo }) { copy(showPlayerLogo = it) },
        choice("symbolOpens", SymbolOpens.entries, { it.symbolOpens }) { copy(symbolOpens = it) },
        switches("enabledSources", offered.sourceSwitches, { it.enabledSources }) { copy(enabledSources = it) },
        switches("webEngines", WebEngine.entries.toSet(), { it.webEngines }) { copy(webEngines = it) },
        switches("labelSources", LabelSource.entries.toSet(), { it.labelSources }) { copy(labelSources = it) },
        switch("showGenres", { it.showGenres }) { copy(showGenres = it) },
        switch("writeInsights", { it.writeInsights }) { copy(writeInsights = it) },
        choice(
            "writeAutomatically",
            WriteAutomatically.entries,
            { it.writeAutomatically },
        ) { copy(writeAutomatically = it) },
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
        choice("prepareNextTrack", PrepareNextTrack.entries, { it.prepareNextTrack }) { copy(prepareNextTrack = it) },
        switch("lookUpInBackground", { it.lookUpInBackground }) { copy(lookUpInBackground = it) },
        switch("artistFactsOncePerSession", { it.artistFactsOncePerSession }) { copy(artistFactsOncePerSession = it) },
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
                    val id = voice?.get("id")?.let(::shortText)
                    val name = voice?.get("name")?.let(::shortText)
                    id?.let { Parsed(ReadAloudVoice(it, name ?: it)) }
                }
            },
        ) { copy(readAloudVoice = it) },
        choice("readAloudMusic", ReadAloudMusic.entries, { it.readAloudMusic }) { copy(readAloudMusic = it) },
        switch("readTrackLineFirst", { it.readTrackLineFirst }) { copy(readTrackLineFirst = it) },
        switch("readNewInsightsAloud", { it.readNewInsightsAloud }) { copy(readNewInsightsAloud = it) },
        switch("searchWithAi", { it.searchWithAi }) { copy(searchWithAi = it) },
        choice("aiWebSearch", AiWebSearch.entries, { it.aiWebSearch }) { copy(aiWebSearch = it) },
        choice("trackLanguageSearch", TrackLanguageSearch.entries, {
            it.trackLanguageSearch
        }) { copy(trackLanguageSearch = it) },
        duration("musicBrainzWait", { it.musicBrainzWait }, { it in Settings.MUSICBRAINZ_WAITS }) {
            copy(musicBrainzWait = it)
        },
        duration("aiRetry", { it.aiRetry }, { it in Settings.AI_RETRIES }) { copy(aiRetry = it) },
        // Each offered source's lifetime, in whole days as its slider has them.
        perKey(
            "cacheLifetimes",
            { s ->
                s.cacheLifetimes
                    .filterKeys { it in offered.cacheSources }
                    .entries
                    .associate { (k, v) -> k.name to JsonPrimitive(v.toString()) }
            },
            { k, v ->
                val source =
                    enumKey(k, SourceId.entries)?.takeIf {
                        it in offered.cacheSources &&
                            it in Settings.DEFAULT_CACHE_LIFETIMES
                    }
                val lifetime = durationOf(v, 1.days)?.takeIf { it.isPositive() }
                if (source != null && lifetime != null) Parsed(source to lifetime) else null
            },
        ) { _, (source, lifetime) -> copy(cacheLifetimes = cacheLifetimes + (source to lifetime)) },
        switch("keepArtistResults", { it.keepArtistResults }) { copy(keepArtistResults = it) },
        one(
            "historyLifetime",
            { it.historyLifetime },
            { nullable(it?.toString()) },
            { v ->
                if (v is JsonNull) {
                    Parsed(
                        null,
                    )
                } else {
                    durationOf(v, 1.days)?.takeIf { it.isPositive() }?.let { Parsed(it) }
                }
            },
        ) { copy(historyLifetime = it) },
        choice("lookUpAfterClear", LookUpAfterClear.entries, { it.lookUpAfterClear }) { copy(lookUpAfterClear = it) },
        duration("sessionGap", { it.sessionGap }, { it.isPositive() }, 1.minutes) { copy(sessionGap = it) },
        choice("sourcesOnNewTrack", NewTrackFold.entries, { it.sourcesOnNewTrack }) { copy(sourcesOnNewTrack = it) },
        choice("itemsOnNewTrack", NewTrackFold.entries, { it.itemsOnNewTrack }) { copy(itemsOnNewTrack = it) },
        switch("onePanel", { it.onePanel }) { copy(onePanel = it) },
        switch("lyricsLinks", { it.lyricsLinks }) { copy(lyricsLinks = it) },
        switch("lyricsSearch", { it.lyricsSearch }) { copy(lyricsSearch = it) },
        switch("showPhoneName", { it.showPhoneName }) { copy(showPhoneName = it) },
        switch("showDiagnostics", { it.showDiagnostics }) { copy(showDiagnostics = it) },
        switch("showGroundingDetails", { it.showGroundingDetails }) { copy(showGroundingDetails = it) },
        choice("reportButton", ReportButton.entries, { it.reportButton }) { copy(reportButton = it) },
        choice("linkStyle", LinkStyle.entries, { it.linkStyle }) { copy(linkStyle = it) },
        switch("markDeveloperItems", { it.markDeveloperItems }) { copy(markDeveloperItems = it) },
        switch("foldDescriptions", { it.foldDescriptions }) { copy(foldDescriptions = it) },
        choice("descriptionsOnReturn", DescriptionsOnReturn.entries, {
            it.descriptionsOnReturn
        }) { copy(descriptionsOnReturn = it) },
        choice("theme", ThemeChoice.entries, { it.theme }) { copy(theme = it) },
        switch("themeButton", { it.themeButton }) { copy(themeButton = it) },
        choice("font", FontChoice.entries, { it.font }) { copy(font = it) },
        switch("introAtStart", { it.introAtStart }) { copy(introAtStart = it) },
        switch("spinLogo", { it.spinLogo }) { copy(spinLogo = it) },
        choice("logoSpeed", LogoSpeed.entries, { it.logoSpeed }) { copy(logoSpeed = it) },
    )
}
