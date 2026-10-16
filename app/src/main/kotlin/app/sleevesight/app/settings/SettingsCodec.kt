package app.sleevesight.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Maps [Settings] to DataStore preferences and back, one key per setting.
 *
 * Only a setting the user moved off its default is stored; one at its default has no key. So a
 * default revised in a later version reaches everyone who has not changed that setting, and a
 * default that depends on something else (#84: Last.fm's genre labels on once its key is set) can
 * be computed rather than frozen.
 *
 * Reading never fails: a value that is missing, of a name this version does not know (written by a
 * later one), or out of range reads as that setting's default, and the others are unaffected.
 */
internal object SettingsCodec {
    private val defaults = Settings()

    private val LAYOUT = stringPreferencesKey("layout")
    private val AI_PROVIDER = stringPreferencesKey("ai.provider")
    private val AI_MODEL = stringPreferencesKey("ai.model")
    private val AI_EFFORT = stringPreferencesKey("ai.effort")
    private val PLAYERS_ON = stringSetPreferencesKey("players.on")
    private val PLAYERS_OFF = stringSetPreferencesKey("players.off")
    private val SOURCES_ON = stringSetPreferencesKey("sources.on")
    private val SOURCES_OFF = stringSetPreferencesKey("sources.off")
    private val WRITE_INSIGHTS = booleanPreferencesKey("insights.write")
    private val WRITE_AUTOMATICALLY = stringPreferencesKey("insights.write_automatically")
    private val CROSSFADE_MS = longPreferencesKey("insights.crossfade_ms")
    private val PREPARE_NEXT = stringPreferencesKey("insights.prepare_next_track")
    private val BACKGROUND = booleanPreferencesKey("lookups.background")
    private val ARTIST_LIFETIME_MS = longPreferencesKey("cache.artist_lifetime_ms")
    private val CACHE_SIZE_LIMIT = longPreferencesKey("cache.size_limit")

    /** `aiEffort` null is the provider's default, which needs a name of its own to be stored. */
    private const val PROVIDER_DEFAULT_EFFORT = "PROVIDER_DEFAULT"

    private fun lifetimeKey(source: SourceId) = longPreferencesKey("cache.lifetime_ms.${source.name}")

    fun read(prefs: Preferences): Settings =
        Settings(
            layout = enumOf(prefs[LAYOUT]) ?: defaults.layout,
            aiProvider = enumOf<ProviderId>(prefs[AI_PROVIDER]),
            aiModel = prefs[AI_MODEL]?.takeIf { it.isNotBlank() },
            followedPlayers = readPlayers(prefs),
            enabledSources = readSources(prefs),
            writeInsights = prefs[WRITE_INSIGHTS] ?: defaults.writeInsights,
            writeAutomatically = enumOf(prefs[WRITE_AUTOMATICALLY]) ?: defaults.writeAutomatically,
            crossfade = readCrossfade(prefs),
            prepareNextTrack = enumOf(prefs[PREPARE_NEXT]) ?: defaults.prepareNextTrack,
            lookUpInBackground = prefs[BACKGROUND] ?: defaults.lookUpInBackground,
            cacheLifetimes = readLifetimes(prefs),
            artistCacheLifetime =
                prefs[ARTIST_LIFETIME_MS]?.takeIf { it > 0 }?.milliseconds ?: defaults.artistCacheLifetime,
            cacheSizeLimit = prefs[CACHE_SIZE_LIMIT]?.takeIf { it >= 0 } ?: defaults.cacheSizeLimit,
            aiEffort = readEffort(prefs),
        )

    fun write(
        settings: Settings,
        prefs: MutablePreferences,
    ) {
        prefs.put(LAYOUT, settings.layout.name, defaults.layout.name)
        prefs.put(AI_PROVIDER, settings.aiProvider?.name, null)
        prefs.put(AI_MODEL, settings.aiModel?.takeIf { it.isNotBlank() }, null)
        prefs.put(AI_EFFORT, settings.aiEffort?.name ?: PROVIDER_DEFAULT_EFFORT, defaults.aiEffort?.name)
        writePlayers(settings.followedPlayers, prefs)
        writeSources(settings.enabledSources, prefs)
        prefs.put(WRITE_INSIGHTS, settings.writeInsights, defaults.writeInsights)
        prefs.put(WRITE_AUTOMATICALLY, settings.writeAutomatically.name, defaults.writeAutomatically.name)
        prefs.put(CROSSFADE_MS, (settings.crossfade as? Crossfade.Fixed)?.value?.inWholeMilliseconds, null)
        prefs.put(PREPARE_NEXT, settings.prepareNextTrack.name, defaults.prepareNextTrack.name)
        prefs.put(BACKGROUND, settings.lookUpInBackground, defaults.lookUpInBackground)
        for (source in Settings.DEFAULT_CACHE_LIFETIMES.keys) {
            prefs.put(
                lifetimeKey(source),
                settings.cacheLifetimes[source]?.inWholeMilliseconds,
                Settings.DEFAULT_CACHE_LIFETIMES[source]?.inWholeMilliseconds,
            )
        }
        prefs.put(
            ARTIST_LIFETIME_MS,
            settings.artistCacheLifetime.inWholeMilliseconds,
            defaults.artistCacheLifetime.inWholeMilliseconds,
        )
        prefs.put(CACHE_SIZE_LIMIT, settings.cacheSizeLimit, defaults.cacheSizeLimit)
    }

    /**
     * *Players to follow* as overrides of the defaults, like *Where to look*: a player listed by
     * default whose switch the user has not moved follows its default, including one a later
     * version adds or whose default it revises. A player seen but not listed by default is stored
     * with its switch, off as first listed.
     */
    private fun readPlayers(prefs: Preferences): Map<String, Boolean> =
        defaults.followedPlayers +
            prefs[PLAYERS_OFF].orEmpty().associateWith { false } +
            prefs[PLAYERS_ON].orEmpty().associateWith { true }

    /**
     * Stores the switches that differ from the defaults: on, where the default is not on; off, where
     * it is not off, newly seen players included. A default player missing from [players] is not
     * followed, so it is stored as off.
     */
    private fun writePlayers(
        players: Map<String, Boolean>,
        prefs: MutablePreferences,
    ) {
        val on = players.filter { (player, follow) -> follow && defaults.followedPlayers[player] != true }.keys
        val off =
            players.filter { (player, follow) -> !follow && defaults.followedPlayers[player] != false }.keys +
                defaults.followedPlayers.filter { (player, follow) -> follow && player !in players }.keys
        prefs.put(PLAYERS_ON, on.ifEmpty { null }, null)
        prefs.put(PLAYERS_OFF, off.ifEmpty { null }, null)
    }

    /**
     * *Where to look* as overrides of each switch's default: a switch the user has not moved follows
     * its default, including one a later version adds or whose default it revises.
     */
    private fun readSources(prefs: Preferences): Set<SourceSwitch> {
        fun switches(key: Preferences.Key<Set<String>>) = prefs[key].orEmpty().mapNotNull { enumOf<SourceSwitch>(it) }
        return defaults.enabledSources + switches(SOURCES_ON) - switches(SOURCES_OFF).toSet()
    }

    private fun writeSources(
        enabled: Set<SourceSwitch>,
        prefs: MutablePreferences,
    ) {
        val on = (enabled - defaults.enabledSources).map { it.name }.toSet()
        val off = (defaults.enabledSources - enabled).map { it.name }.toSet()
        prefs.put(SOURCES_ON, on.ifEmpty { null }, null)
        prefs.put(SOURCES_OFF, off.ifEmpty { null }, null)
    }

    private fun readCrossfade(prefs: Preferences): Crossfade {
        val value = prefs[CROSSFADE_MS]?.milliseconds ?: return Crossfade.Autodetect
        return if (value in Duration.ZERO..Crossfade.Fixed.MAX) Crossfade.Fixed(value) else Crossfade.Autodetect
    }

    private fun readEffort(prefs: Preferences): ReasoningEffort? =
        when (val stored = prefs[AI_EFFORT]) {
            PROVIDER_DEFAULT_EFFORT -> null
            else -> enumOf<ReasoningEffort>(stored) ?: defaults.aiEffort
        }

    /**
     * The defaults, with each source's lifetime the user changed. A source without a default
     * lifetime is never cached, so it has no setting.
     */
    private fun readLifetimes(prefs: Preferences): Map<SourceId, Duration> =
        Settings.DEFAULT_CACHE_LIFETIMES.mapValues { (source, default) ->
            prefs[lifetimeKey(source)]?.takeIf { it > 0 }?.milliseconds ?: default
        }
}

private inline fun <reified E : Enum<E>> enumOf(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

/** Stores [value], or removes the key when it is the default or null. */
private fun <T : Any> MutablePreferences.put(
    key: Preferences.Key<T>,
    value: T?,
    default: T?,
) {
    if (value == null || value == default) remove(key) else set(key, value)
}
