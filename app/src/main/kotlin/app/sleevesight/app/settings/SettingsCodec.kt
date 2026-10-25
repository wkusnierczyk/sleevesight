package app.sleevesight.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * later one), or out of range reads as that setting's default, and the others are unaffected. One
 * exception: *Developer mode* (#444), stored by no earlier version, reads as on in a file with
 * *Diagnostics* on, so someone who used the diagnostics keeps the way to them.
 *
 * Writing keeps what a later version stored, as after a downgrade: [write] rewrites only the
 * settings that changed, so a value this version cannot read stays until the user changes that
 * setting, and in a set of switches only the switches that changed, so the others stay as stored:
 * names this version does not know, and overrides a later version stored against other defaults.
 *
 * The defaults are `:core`'s [Settings], but for those a build sets: [SettingsStore] passes the
 * build's ([SettingsStore.BUILD_DEFAULTS]), and a test may pass others. Since only a setting moved
 * off its default is stored, a default that differs between builds, as developer mode's does
 * (#451), holds wherever the user has not chosen, and a choice either way is kept in either build.
 *
 * The AI service's settings are stored per provider by [AiSettingsCodec].
 */
internal object SettingsCodec {
    private val LAYOUT = stringPreferencesKey("layout")
    private val PLAYERS_ON = stringSetPreferencesKey("players.on")
    private val PLAYERS_OFF = stringSetPreferencesKey("players.off")
    private val SOURCES_ON = stringSetPreferencesKey("sources.on")
    private val SOURCES_OFF = stringSetPreferencesKey("sources.off")
    private val ENGINES_ON = stringSetPreferencesKey("web.engines.on")
    private val ENGINES_OFF = stringSetPreferencesKey("web.engines.off")
    private val LABELS_ON = stringSetPreferencesKey("labels.on")
    private val LABELS_OFF = stringSetPreferencesKey("labels.off")
    private val SHOW_GENRES = booleanPreferencesKey("genres.show")
    private val WRITE_INSIGHTS = booleanPreferencesKey("insights.write")
    private val WRITE_AUTOMATICALLY = stringPreferencesKey("insights.write_automatically")
    private val PAUSE_AUTO_WRITE = intPreferencesKey("insights.pause_auto_write_after")
    private val CROSSFADE_MS = longPreferencesKey("insights.crossfade_ms")
    private val PREPARE_NEXT = stringPreferencesKey("insights.prepare_next_track")
    private val BACKGROUND = booleanPreferencesKey("lookups.background")
    private val KEEP_ARTIST_RESULTS = booleanPreferencesKey("cache.keep_artist_results")
    private val ARTIST_LIFETIME_MS = longPreferencesKey("cache.artist_lifetime_ms")
    private val CACHE_SIZE_LIMIT = longPreferencesKey("cache.size_limit")
    private val HISTORY_LIFETIME_MS = longPreferencesKey("history.lifetime_ms")
    private val SESSION_GAP_MS = longPreferencesKey("history.session_gap_ms")
    private val KEEP_SOURCES_UNFOLDED = booleanPreferencesKey("home.keep_sources_unfolded")
    private val DIAGNOSTICS = booleanPreferencesKey("developer.diagnostics")
    private val DEVELOPER_MODE = booleanPreferencesKey("developer.mode")
    private val THEME = stringPreferencesKey("display.theme")
    private val FONT = stringPreferencesKey("display.font")
    private val INTRO = booleanPreferencesKey("display.intro")

    /** `historyLifetime` null is *Forever*, which needs a value of its own to be stored. */
    private const val FOREVER = -1L

    private fun lifetimeKey(source: SourceId) = longPreferencesKey("cache.lifetime_ms.${source.name}")

    @Suppress("CyclomaticComplexMethod") // One line per setting, each its own key.
    fun read(
        prefs: Preferences,
        defaults: Settings = Settings(),
    ): Settings =
        Settings(
            layout = enumOf(prefs[LAYOUT]) ?: defaults.layout,
            aiProvider = AiSettingsCodec.readProvider(prefs),
            aiModels = AiSettingsCodec.readModels(prefs),
            aiEfforts = AiSettingsCodec.readEfforts(prefs),
            followedPlayers = readPlayers(prefs, defaults),
            enabledSources = readSwitches(prefs, SOURCES_ON, SOURCES_OFF, defaults.enabledSources),
            webEngines = readSwitches(prefs, ENGINES_ON, ENGINES_OFF, defaults.webEngines),
            labelSources = readSwitches(prefs, LABELS_ON, LABELS_OFF, defaults.labelSources),
            showGenres = prefs[SHOW_GENRES] ?: defaults.showGenres,
            writeInsights = prefs[WRITE_INSIGHTS] ?: defaults.writeInsights,
            writeAutomatically = enumOf(prefs[WRITE_AUTOMATICALLY]) ?: defaults.writeAutomatically,
            pauseAutoWriteAfter = pauseOf(prefs[PAUSE_AUTO_WRITE], defaults.pauseAutoWriteAfter),
            crossfade = readCrossfade(prefs),
            prepareNextTrack = enumOf(prefs[PREPARE_NEXT]) ?: defaults.prepareNextTrack,
            lookUpInBackground = prefs[BACKGROUND] ?: defaults.lookUpInBackground,
            cacheLifetimes = readLifetimes(prefs),
            keepArtistResults = prefs[KEEP_ARTIST_RESULTS] ?: defaults.keepArtistResults,
            artistCacheLifetime =
                prefs[ARTIST_LIFETIME_MS]?.takeIf { it > 0 }?.milliseconds ?: defaults.artistCacheLifetime,
            cacheSizeLimit = prefs[CACHE_SIZE_LIMIT]?.takeIf { it >= 0 } ?: defaults.cacheSizeLimit,
            historyLifetime =
                when (val stored = prefs[HISTORY_LIFETIME_MS]) {
                    FOREVER -> null
                    else -> stored?.takeIf { it > 0 }?.milliseconds ?: defaults.historyLifetime
                },
            sessionGap = prefs[SESSION_GAP_MS]?.takeIf { it > 0 }?.milliseconds ?: defaults.sessionGap,
            keepSourcesUnfolded = prefs[KEEP_SOURCES_UNFOLDED] ?: defaults.keepSourcesUnfolded,
            showDiagnostics = prefs[DIAGNOSTICS] ?: defaults.showDiagnostics,
            developerMode = developerModeOf(prefs, defaults),
            theme = enumOf(prefs[THEME]) ?: defaults.theme,
            font = enumOf(prefs[FONT]) ?: defaults.font,
            introAtStart = prefs[INTRO] ?: defaults.introAtStart,
        )

    /**
     * Stores [settings] in [prefs], rewriting only the settings that differ from what [prefs] reads
     * as, so a value a later version stored, which this one reads as the default, stays until the
     * user changes that setting. [defaults] are those [read] reads with.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One line per setting, each its own key.
    fun write(
        settings: Settings,
        prefs: MutablePreferences,
        defaults: Settings = Settings(),
    ) {
        val stored = read(prefs, defaults)

        fun moved(of: (Settings) -> Any?) = of(settings) != of(stored)

        // The names whose value differs from `before` to `after`, each with its value after (null:
        // no longer listed, or a switch turned off).
        fun <T> changes(
            before: Map<String, T>,
            after: Map<String, T>,
        ) = (before.keys + after.keys).filter { before[it] != after[it] }.associateWith { after[it] }

        // A set of switches: the names of those on, each overriding its default only if it changed.
        fun <E : Enum<E>> switches(
            onKey: Preferences.Key<Set<String>>,
            offKey: Preferences.Key<Set<String>>,
            of: (Settings) -> Set<E>,
        ) {
            val names = { s: Settings -> of(s).associate { it.name to true } }
            val onByDefault = of(defaults).map { it.name }.toSet()
            writeOverrides(changes(names(stored), names(settings)), { it in onByDefault }, prefs, onKey, offKey)
        }

        if (moved { it.layout }) prefs.put(LAYOUT, settings.layout.name, defaults.layout.name)
        AiSettingsCodec.write(settings, stored, prefs)
        // A default player missing from the players is not followed, so it is stored as off.
        val players = changes(stored.followedPlayers, settings.followedPlayers)
        writeOverrides(players, { defaults.followedPlayers[it] }, prefs, PLAYERS_ON, PLAYERS_OFF)
        switches(SOURCES_ON, SOURCES_OFF) { it.enabledSources }
        switches(ENGINES_ON, ENGINES_OFF) { it.webEngines }
        switches(LABELS_ON, LABELS_OFF) { it.labelSources }
        if (moved { it.showGenres }) prefs.put(SHOW_GENRES, settings.showGenres, defaults.showGenres)
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
        if (moved { it.keepArtistResults }) {
            prefs.put(KEEP_ARTIST_RESULTS, settings.keepArtistResults, defaults.keepArtistResults)
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
        if (moved { it.keepSourcesUnfolded }) {
            prefs.put(KEEP_SOURCES_UNFOLDED, settings.keepSourcesUnfolded, defaults.keepSourcesUnfolded)
        }
        if (moved { it.showDiagnostics }) prefs.put(DIAGNOSTICS, settings.showDiagnostics, defaults.showDiagnostics)
        // Without its key, it reads as *Diagnostics*'s key, or with neither as its default
        // ([developerModeOf]), so the key is kept only where that would read otherwise; either
        // moving can change which. Against its own default, not *Diagnostics*'s: in a build where
        // it is on by default (#451), developer mode turned off is stored as off.
        if (moved { it.developerMode } || moved { it.showDiagnostics }) {
            prefs.put(DEVELOPER_MODE, settings.developerMode, prefs[DIAGNOSTICS] ?: defaults.developerMode)
        }
        if (moved { it.theme }) prefs.put(THEME, settings.theme.name, defaults.theme.name)
        if (moved { it.font }) prefs.put(FONT, settings.font.name, defaults.font.name)
        if (moved { it.introAtStart }) prefs.put(INTRO, settings.introAtStart, defaults.introAtStart)
    }

    /**
     * *Developer mode* as stored, or, with no key, as *Diagnostics* is: a file from before developer
     * mode (#444) with *Diagnostics* on reads as on, so its user keeps the way to the diagnostics.
     * With neither key, the build's default ([defaults]).
     */
    private fun developerModeOf(
        prefs: Preferences,
        defaults: Settings,
    ): Boolean = prefs[DEVELOPER_MODE] ?: prefs[DIAGNOSTICS] ?: defaults.developerMode

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
     *
     * A phone call's package ([Settings.isCall]) is never read, whichever key holds it: an older
     * version listed it like any player, and a file with it switched on must not follow it (#435).
     */
    private fun readPlayers(
        prefs: Preferences,
        defaults: Settings,
    ): Map<String, Boolean> =
        (
            defaults.followedPlayers +
                prefs[PLAYERS_ON].orEmpty().associateWith { true } +
                prefs[PLAYERS_OFF].orEmpty().associateWith { false }
        ).filterKeys { !Settings.isCall(it) }

    /**
     * Drops from *Players to follow* what an older version listed by the old rule, any app with a
     * media session (#435): each player listed off that is not listed by default, so it is listed
     * again only once it reports music ([SettingsStore.playerSeen]), and every phone call's package,
     * whatever its switch. A player switched on stays, so the user's choice is not undone; one
     * listed by default stays too, since dropping its `off` would switch it back on. Rewrites only
     * the two players' keys.
     */
    fun prunePlayers(prefs: MutablePreferences) {
        val listedByDefault = Settings().followedPlayers.keys
        // A player in both keys reads as off, so it is not kept as on.
        val on = (prefs[PLAYERS_ON].orEmpty() - prefs[PLAYERS_OFF].orEmpty()).filterNot(Settings::isCall).toSet()
        val off = prefs[PLAYERS_OFF].orEmpty().filter { it in listedByDefault && !Settings.isCall(it) }.toSet()
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
     * Rewrites only the [changed] names of an `on` and `off` pair, each to its new value (null for
     * one no longer listed) against its [default] (null for one not listed by default): on where
     * the default is not on, off where it is not off, a default player no longer listed off, and
     * otherwise neither. Every other name stays as stored, one this version does not know or one a
     * later version stored against another default alike, so a downgrade does not lose it.
     */
    private fun writeOverrides(
        changed: Map<String, Boolean?>,
        default: (String) -> Boolean?,
        prefs: MutablePreferences,
        onKey: Preferences.Key<Set<String>>,
        offKey: Preferences.Key<Set<String>>,
    ) {
        if (changed.isEmpty()) return
        val on = prefs[onKey].orEmpty() - changed.keys
        val off = prefs[offKey].orEmpty() - changed.keys
        val turnedOn = changed.filter { (name, value) -> value == true && default(name) != true }.keys
        val offNow = { name: String, value: Boolean? ->
            (value == false && default(name) != false) || (value == null && default(name) == true)
        }
        val turnedOff = changed.filter { (name, value) -> offNow(name, value) }.keys
        prefs.put(onKey, (on + turnedOn).ifEmpty { null }, null)
        prefs.put(offKey, (off + turnedOff).ifEmpty { null }, null)
    }

    private fun readCrossfade(prefs: Preferences): Crossfade {
        val value = prefs[CROSSFADE_MS]?.milliseconds ?: return Crossfade.Autodetect
        return if (value in Duration.ZERO..Crossfade.Fixed.MAX) Crossfade.Fixed(value) else Crossfade.Autodetect
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

internal inline fun <reified E : Enum<E>> enumOf(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

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
internal fun <T : Any> MutablePreferences.put(
    key: Preferences.Key<T>,
    value: T?,
    default: T?,
) {
    if (value == null || value == default) remove(key) else set(key, value)
}
