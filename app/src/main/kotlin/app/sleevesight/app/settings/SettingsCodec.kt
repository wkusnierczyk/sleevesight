package app.sleevesight.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Maps [Settings] to DataStore preferences and back, one key per setting.
 *
 * Only a setting the user moved off its default is stored; one at its default has no key. So a
 * default revised in a later version reaches everyone who has not changed that setting (#100: the
 * MusicBrainz labels may be off by default in the release). A set of switches (*Where to look*,
 * the web search engines, the genre label sources) is stored as the switches moved on or off
 * against their defaults, so one the user has not moved, or one a later version adds, follows its
 * default; *Players to follow* is stored the same way, by package name ([readPlayers]).
 *
 * Reading never fails: a value that is missing, of a name this version does not know (written by a
 * later one), or out of range reads as that setting's default, and the others are unaffected.
 *
 * Writing keeps what a later version stored, as after a downgrade: [write] rewrites only the
 * settings that changed, so a value this version cannot read stays until the user changes that
 * setting, and a set of switches keeps the names it does not know.
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
    private val ENGINES_ON = stringSetPreferencesKey("web.engines.on")
    private val ENGINES_OFF = stringSetPreferencesKey("web.engines.off")
    private val LABELS_ON = stringSetPreferencesKey("labels.on")
    private val LABELS_OFF = stringSetPreferencesKey("labels.off")
    private val WRITE_INSIGHTS = booleanPreferencesKey("insights.write")
    private val WRITE_AUTOMATICALLY = stringPreferencesKey("insights.write_automatically")
    private val PAUSE_AUTO_WRITE = intPreferencesKey("insights.pause_auto_write_after")
    private val CROSSFADE_MS = longPreferencesKey("insights.crossfade_ms")
    private val PREPARE_NEXT = stringPreferencesKey("insights.prepare_next_track")
    private val BACKGROUND = booleanPreferencesKey("lookups.background")
    private val ARTIST_LIFETIME_MS = longPreferencesKey("cache.artist_lifetime_ms")
    private val CACHE_SIZE_LIMIT = longPreferencesKey("cache.size_limit")
    private val HISTORY_LIFETIME_MS = longPreferencesKey("history.lifetime_ms")
    private val SESSION_GAP_MS = longPreferencesKey("history.session_gap_ms")
    private val DIAGNOSTICS = booleanPreferencesKey("developer.diagnostics")

    /** `aiEffort` null is the provider's default, which needs a name of its own to be stored. */
    private const val PROVIDER_DEFAULT_EFFORT = "PROVIDER_DEFAULT"

    /** `historyLifetime` null is *Forever*, which needs a value of its own to be stored. */
    private const val FOREVER = -1L

    private fun lifetimeKey(source: SourceId) = longPreferencesKey("cache.lifetime_ms.${source.name}")

    fun read(prefs: Preferences): Settings =
        Settings(
            layout = enumOf(prefs[LAYOUT]) ?: defaults.layout,
            aiProvider = enumOf<ProviderId>(prefs[AI_PROVIDER]),
            aiModel = prefs[AI_MODEL]?.takeIf { it.isNotBlank() },
            followedPlayers = readPlayers(prefs),
            enabledSources = readSwitches(prefs, SOURCES_ON, SOURCES_OFF, defaults.enabledSources),
            webEngines = readSwitches(prefs, ENGINES_ON, ENGINES_OFF, defaults.webEngines),
            labelSources = readSwitches(prefs, LABELS_ON, LABELS_OFF, defaults.labelSources),
            writeInsights = prefs[WRITE_INSIGHTS] ?: defaults.writeInsights,
            writeAutomatically = enumOf(prefs[WRITE_AUTOMATICALLY]) ?: defaults.writeAutomatically,
            pauseAutoWriteAfter = pauseOf(prefs[PAUSE_AUTO_WRITE], defaults.pauseAutoWriteAfter),
            crossfade = readCrossfade(prefs),
            prepareNextTrack = enumOf(prefs[PREPARE_NEXT]) ?: defaults.prepareNextTrack,
            lookUpInBackground = prefs[BACKGROUND] ?: defaults.lookUpInBackground,
            cacheLifetimes = readLifetimes(prefs),
            artistCacheLifetime =
                prefs[ARTIST_LIFETIME_MS]?.takeIf { it > 0 }?.milliseconds ?: defaults.artistCacheLifetime,
            cacheSizeLimit = prefs[CACHE_SIZE_LIMIT]?.takeIf { it >= 0 } ?: defaults.cacheSizeLimit,
            aiEffort = readEffort(prefs),
            historyLifetime =
                when (val stored = prefs[HISTORY_LIFETIME_MS]) {
                    FOREVER -> null
                    else -> stored?.takeIf { it > 0 }?.milliseconds ?: defaults.historyLifetime
                },
            sessionGap = prefs[SESSION_GAP_MS]?.takeIf { it > 0 }?.milliseconds ?: defaults.sessionGap,
            showDiagnostics = prefs[DIAGNOSTICS] ?: defaults.showDiagnostics,
        )

    /**
     * Stores [settings] in [prefs], rewriting only the settings that differ from what [prefs] reads
     * as, so a value a later version stored, which this one reads as the default, stays until the
     * user changes that setting.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One line per setting, each its own key.
    fun write(
        settings: Settings,
        prefs: MutablePreferences,
    ) {
        val stored = read(prefs)

        fun moved(of: (Settings) -> Any?) = of(settings) != of(stored)
        if (moved { it.layout }) prefs.put(LAYOUT, settings.layout.name, defaults.layout.name)
        if (moved { it.aiProvider }) prefs.put(AI_PROVIDER, settings.aiProvider?.name, null)
        if (moved { it.aiModel }) prefs.put(AI_MODEL, settings.aiModel?.takeIf { it.isNotBlank() }, null)
        if (moved { it.aiEffort }) {
            prefs.put(AI_EFFORT, settings.aiEffort?.name ?: PROVIDER_DEFAULT_EFFORT, defaults.aiEffort?.name)
        }
        if (moved { it.followedPlayers }) writePlayers(settings.followedPlayers, prefs)
        if (moved { it.enabledSources }) {
            writeSwitches(settings.enabledSources, defaults.enabledSources, prefs, SOURCES_ON, SOURCES_OFF)
        }
        if (moved { it.webEngines }) {
            writeSwitches(settings.webEngines, defaults.webEngines, prefs, ENGINES_ON, ENGINES_OFF)
        }
        if (moved { it.labelSources }) {
            writeSwitches(settings.labelSources, defaults.labelSources, prefs, LABELS_ON, LABELS_OFF)
        }
        if (moved { it.writeInsights }) prefs.put(WRITE_INSIGHTS, settings.writeInsights, defaults.writeInsights)
        if (moved { it.writeAutomatically }) {
            prefs.put(WRITE_AUTOMATICALLY, settings.writeAutomatically.name, defaults.writeAutomatically.name)
        }
        if (moved { it.pauseAutoWriteAfter }) {
            prefs.put(
                PAUSE_AUTO_WRITE,
                settings.pauseAutoWriteAfter ?: NEVER_PAUSE,
                defaults.pauseAutoWriteAfter ?: NEVER_PAUSE,
            )
        }
        if (moved { it.crossfade }) {
            prefs.put(CROSSFADE_MS, (settings.crossfade as? Crossfade.Fixed)?.value?.inWholeMilliseconds, null)
        }
        if (moved { it.prepareNextTrack }) {
            prefs.put(PREPARE_NEXT, settings.prepareNextTrack.name, defaults.prepareNextTrack.name)
        }
        if (moved { it.lookUpInBackground }) {
            prefs.put(BACKGROUND, settings.lookUpInBackground, defaults.lookUpInBackground)
        }
        for (source in Settings.DEFAULT_CACHE_LIFETIMES.keys) {
            if (!moved { it.cacheLifetimes[source] }) continue
            prefs.put(
                lifetimeKey(source),
                settings.cacheLifetimes[source]?.inWholeMilliseconds,
                Settings.DEFAULT_CACHE_LIFETIMES[source]?.inWholeMilliseconds,
            )
        }
        if (moved { it.artistCacheLifetime }) {
            prefs.put(
                ARTIST_LIFETIME_MS,
                settings.artistCacheLifetime.inWholeMilliseconds,
                defaults.artistCacheLifetime.inWholeMilliseconds,
            )
        }
        if (moved { it.cacheSizeLimit }) prefs.put(CACHE_SIZE_LIMIT, settings.cacheSizeLimit, defaults.cacheSizeLimit)
        if (moved { it.historyLifetime }) {
            prefs.put(
                HISTORY_LIFETIME_MS,
                settings.historyLifetime?.inWholeMilliseconds ?: FOREVER,
                defaults.historyLifetime?.inWholeMilliseconds ?: FOREVER,
            )
        }
        if (moved { it.sessionGap }) {
            prefs.put(SESSION_GAP_MS, settings.sessionGap.inWholeMilliseconds, defaults.sessionGap.inWholeMilliseconds)
        }
        if (moved { it.showDiagnostics }) prefs.put(DIAGNOSTICS, settings.showDiagnostics, defaults.showDiagnostics)
    }

    /**
     * *Players to follow* as overrides of the defaults, with `on` and `off` keys as the switch sets
     * have ([readSwitches]): a player listed by default that this phone has not already listed off
     * follows its default, including one a later version adds or whose default it revises. A player
     * seen but not listed by default is stored with its switch, off as first listed (`players.off`,
     * by [SettingsStore.playerSeen]), so a later version that lists it by default does not switch it
     * on. A player in both keys, which no write stores, is off, as a switch in both of a set's keys
     * is: a player is followed only on the user's choice. Not through [readSwitches]: the players
     * are package names, not an enum, and a player listed but switched off is a state a set of
     * switches turned on cannot hold.
     */
    private fun readPlayers(prefs: Preferences): Map<String, Boolean> =
        defaults.followedPlayers +
            prefs[PLAYERS_ON].orEmpty().associateWith { true } +
            prefs[PLAYERS_OFF].orEmpty().associateWith { false }

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
     * A set of switches as overrides of each switch's [default]: a switch the user has not moved
     * follows its default, including one a later version adds or whose default it revises.
     */
    private inline fun <reified E : Enum<E>> readSwitches(
        prefs: Preferences,
        on: Preferences.Key<Set<String>>,
        off: Preferences.Key<Set<String>>,
        default: Set<E>,
    ): Set<E> {
        val moved = { key: Preferences.Key<Set<String>> -> prefs[key].orEmpty().mapNotNull { enumOf<E>(it) } }
        return default + moved(on) - moved(off).toSet()
    }

    /**
     * Stores the switches of [enabled] that differ from their [default], with each name stored
     * before that this version does not know (a later version's switch), so a downgrade does not
     * lose it.
     */
    private inline fun <reified E : Enum<E>> writeSwitches(
        enabled: Set<E>,
        default: Set<E>,
        prefs: MutablePreferences,
        onKey: Preferences.Key<Set<String>>,
        offKey: Preferences.Key<Set<String>>,
    ) {
        val unknown = { key: Preferences.Key<Set<String>> -> prefs[key].orEmpty().filter { enumOf<E>(it) == null } }
        val on = (enabled - default).map { it.name }.toSet() + unknown(onKey)
        val off = (default - enabled).map { it.name }.toSet() + unknown(offKey)
        prefs.put(onKey, on.ifEmpty { null }, null)
        prefs.put(offKey, off.ifEmpty { null }, null)
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

/** `pauseAutoWriteAfter` null is *Off*, which needs a value of its own to be stored. */
private const val NEVER_PAUSE = 0

/** *Pause automatic writing after* as [stored]: *Off*, a positive count, or else the [default]. */
private fun pauseOf(
    stored: Int?,
    default: Int?,
): Int? =
    when (stored) {
        NEVER_PAUSE -> null
        else -> stored?.takeIf { it > 0 } ?: default
    }

/** Stores [value], or removes the key when it is the default or null. */
private fun <T : Any> MutablePreferences.put(
    key: Preferences.Key<T>,
    value: T?,
    default: T?,
) {
    if (value == null || value == default) remove(key) else set(key, value)
}
