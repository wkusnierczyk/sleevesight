package app.sleevesight.core.settings

import app.sleevesight.core.ai.EffortScale
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The user's settings, with their defaults (`design/design.md`, *Settings*; architecture,
 * driver 6). `:app` reads them from DataStore into this type, and `:cli` uses the defaults, so the
 * two run the pipeline the same way. Secrets (API keys, tokens) are not settings.
 *
 * **The AI service** (#227) is one row per provider, each with its own key, model and reasoning
 * effort, and a switch; the switches are exclusive. [aiProvider] is the provider switched on, or
 * null for none. [aiModels] and [aiEfforts] hold each provider's choices whether it is switched on
 * or not, so switching back to one restores them; read them through [model] and [effort], which
 * fill in the defaults. A provider joins with its client in `:ai`, its default model in
 * [DEFAULT_MODELS] and its effort scale in [EFFORT_SCALES] (and, in `:app`, its key and its entry
 * in the providers offered); nothing here changes for it.
 *
 * @property aiProvider the AI service switched on, whose model and effort the pipeline uses; null
 *   is none, and no insight is written.
 * @property aiModels the model each provider was set to; a provider not in it uses its default
 *   ([DEFAULT_MODELS]). Read [model]; set with [withModel].
 * @property aiEfforts the reasoning effort each provider was set to, where null is the provider's
 *   own default (no effort sent); a provider not in it uses its scale's default ([EFFORT_SCALES]).
 *   Read [effort]; set with [withEffort].
 * @property followedPlayers one switch per player whose session reported music, by package name;
 *   never a phone call's ([isCall]). See [follows].
 * @property enabledSources the user's *Sources* switches: what the model may use. Ask
 *   [isEnabled] about a [SourceId], which maps it to its switch.
 * @property webEngines the web search engines switched on under *Web search* (D11); web search runs
 *   only with one on. See [DEFAULT_WEB_ENGINES].
 * @property labelSources the sources whose tags give genre labels (D12, #84); ask [givesLabels],
 *   which also needs the source on under *Sources* and [showGenres] on. See
 *   [DEFAULT_LABEL_SOURCES].
 * @property showGenres *Show genres* (#444): off, no genre labels are asked for from any source,
 *   and the home screen shows none. On by default.
 * @property writeInsights the master switch: off means no AI calls at all; evidence is still shown.
 * @property pauseAutoWriteAfter *Saver* (#203): the track changes reported
 *   while Sleevesight is not on screen that are written as usual; at the next, the user's first
 *   action expires, so nothing more is written without a new tap. Null is *Off*, and the first
 *   action lasts as long as the process.
 *   See [DEFAULT_PAUSE_AUTO_WRITE_AFTER].
 * @property cacheLifetimes the retrieval cache lifetime per source (D9). A source with none (the
 *   pseudo-sources, which retrieve nothing) is not cached.
 * @property keepArtistResults *Keep what sources said about each artist* (#420): whether what the
 *   sources said about an artist (Last.fm's answers and tags, the Wikipedia artist article and its
 *   Wikidata facts, MusicBrainz's tags) is kept on the phone: per artist, for the next track by the
 *   artist, and in each track's kept findings. Off by default, for new installs and for those
 *   upgraded from a version that kept it unasked: kept together, it amounts to a record of the
 *   artists heard, which a user should choose to keep. Off, none of it is kept (nor the sources'
 *   verdicts on artists' pages, nor the artists' MusicBrainz ids), each track's artists are looked
 *   up again, and of a track's findings only the web search's findings (pages about the recording,
 *   which may name or describe its artists) and those of a lookup that found no artist to look up
 *   are kept (`SourceResult.trackOnly`), so a track played before is looked up again at MusicBrainz
 *   (its match held in memory for half an hour only), Wikipedia and Last.fm, unless it has no
 *   credited artist; and what was kept is deleted. What stays is the record of which tracks were
 *   played (the history, the track cache's keys) and the insights written, which may retell some of
 *   it. On, it is kept for its sources' lifetimes ([cacheLifetimes]), so fewer requests about
 *   artists leave the phone; each track's own lookups still send its artists' names. Either way
 *   MusicBrainz's answers, its artist searches included, are held in memory for half an hour
 *   (`RememberedMusicBrainz`), for the track about to play, and never stored. It covers the artist
 *   resolutions kept for [artistCacheLifetime] too.
 * @property artistCacheLifetime how long a credited artist's MusicBrainz id, or the finding that it
 *   has none or several, is kept (architecture, *Cache and history*: "long, months"), while
 *   [keepArtistResults] is on.
 * @property cacheSizeLimit the most the caches may hold, in bytes of keys and values; past it the
 *   least recently used entries are evicted. See [DEFAULT_CACHE_SIZE_LIMIT].
 * @property historyLifetime *Keep history for*: how long the session history and the track changes
 *   are kept, counted back from today to when a track began to play and a change began; null keeps
 *   them for ever. See [DEFAULT_HISTORY_LIFETIME].
 * @property sessionGap *New session idle trigger*: how long a break in listening, from
 *   when the last track was last heard, ends a listening session. See [DEFAULT_SESSION_GAP].
 * @property keepSourcesUnfolded *Keep Sources as you left them* (#269): whether the *Sources* list
 *   under an insight keeps, for the next track, how it was left: folded, a screenful, or all its
 *   lines. Off by default: each new track's list starts folded, as the first one did, since a
 *   long list makes the screen hard to scan, and unfolding it to check one insight's sources says
 *   nothing about the next. On, a list shown past its first screenful but not in full comes back
 *   at its first screenful. The fold is kept through a rotation and when Android restores the
 *   screen it closed, not when the app is started afresh.
 * @property showDiagnostics *Diagnostics*, a developer setting: whether the settings lead to the
 *   diagnostics screen (`design/design.md`, *Diagnostics*). Off: a listener has no use for it.
 * @property developerMode *Developer mode* (#444): whether the settings show their *Developer*
 *   section, the options a developer or tester may want and a listener will not. Turned on by seven
 *   taps on *Version*, off by its switch, which turns off every developer option with it
 *   ([withoutDeveloperMode]). Off by default.
 * @property theme *Theme* (#444): light or dark as the system is, or always one of them.
 * @property font *Font* (#377): the app's own font, Roboto, bundled with it, or the phone's system
 *   font. See [FontChoice].
 * @property introAtStart *Logo intro at start* (#383): whether the app, opened from the launcher
 *   while it is not open (not a return to it), plays one of the logo's intros before showing its
 *   screen. On by default: it is a few seconds, and a tap, Back or any key ends it; it never plays
 *   while the phone's *Remove animations* is on. Off, for someone who would rather not wait.
 */
data class Settings(
    val layout: Layout = Layout.SEPARATE_PAGES,
    val aiProvider: ProviderId? = null,
    val aiModels: Map<ProviderId, String> = emptyMap(),
    val aiEfforts: Map<ProviderId, ReasoningEffort?> = emptyMap(),
    val followedPlayers: Map<String, Boolean> = mapOf(SPOTIFY to true),
    val enabledSources: Set<SourceSwitch> = DEFAULT_SOURCES,
    val webEngines: Set<WebEngine> = DEFAULT_WEB_ENGINES,
    val labelSources: Set<LabelSource> = DEFAULT_LABEL_SOURCES,
    val showGenres: Boolean = true,
    val writeInsights: Boolean = true,
    val writeAutomatically: WriteAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE,
    val pauseAutoWriteAfter: Int? = DEFAULT_PAUSE_AUTO_WRITE_AFTER,
    val crossfade: Crossfade = Crossfade.Autodetect,
    val prepareNextTrack: PrepareNextTrack = PrepareNextTrack.EVIDENCE,
    val lookUpInBackground: Boolean = false,
    val cacheLifetimes: Map<SourceId, Duration> = DEFAULT_CACHE_LIFETIMES,
    val keepArtistResults: Boolean = false,
    val artistCacheLifetime: Duration = DEFAULT_ARTIST_CACHE_LIFETIME,
    val cacheSizeLimit: Long = DEFAULT_CACHE_SIZE_LIMIT,
    val historyLifetime: Duration? = DEFAULT_HISTORY_LIFETIME,
    val sessionGap: Duration = DEFAULT_SESSION_GAP,
    val keepSourcesUnfolded: Boolean = false,
    val showDiagnostics: Boolean = false,
    val developerMode: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val font: FontChoice = FontChoice.APP_FONT,
    val introAtStart: Boolean = true,
) {
    init {
        require(cacheSizeLimit >= 0) { "The cache size limit must not be negative, was $cacheSizeLimit" }
        require(historyLifetime == null || historyLifetime.isPositive()) {
            "The history lifetime must be positive, was $historyLifetime"
        }
        require(sessionGap.isPositive()) { "The session gap must be positive, was $sessionGap" }
        require(pauseAutoWriteAfter == null || pauseAutoWriteAfter > 0) {
            "Automatic writing must pause after a positive number of track changes, was $pauseAutoWriteAfter"
        }
    }

    /**
     * Whether events from [player] (a package name) are followed; a player not yet seen is not, and
     * a phone call's ([isCall]) never is, whatever its switch.
     */
    fun follows(player: String): Boolean = !isCall(player) && followedPlayers[player] ?: false

    /** [provider]'s model: the one it was set to, or its default ([DEFAULT_MODELS]), or none. */
    fun model(provider: ProviderId): String? = aiModels[provider] ?: DEFAULT_MODELS[provider]

    /** The model to write with: the switched-on provider's ([model]), or none. */
    val model: String? get() = aiProvider?.let { model(it) }

    /**
     * [provider]'s reasoning effort: the one it was set to, if its scale offers it, or its scale's
     * default; null is the provider's own default, and so is a provider with no scale.
     */
    fun effort(provider: ProviderId): ReasoningEffort? {
        val scale = EFFORT_SCALES[provider] ?: return null
        return if (provider in aiEfforts) scale.coerce(aiEfforts[provider]) else scale.default
    }

    /** The reasoning effort to write with: the switched-on provider's ([effort]), or none. */
    val effort: ReasoningEffort? get() = aiProvider?.let { effort(it) }

    /** These settings with [provider]'s model set to [model]; null or blank is its default. */
    fun withModel(
        provider: ProviderId,
        model: String?,
    ): Settings {
        val chosen = model?.takeIf { it.isNotBlank() }
        return copy(aiModels = if (chosen == null) aiModels - provider else aiModels + (provider to chosen))
    }

    /**
     * These settings with [provider]'s reasoning effort set to [effort] (null: the provider's own
     * default), or its scale's default for a level the scale does not offer. The scale's default is
     * kept as no choice, so a revised default reaches it; a provider with no scale keeps none.
     */
    fun withEffort(
        provider: ProviderId,
        effort: ReasoningEffort?,
    ): Settings {
        val scale = EFFORT_SCALES[provider]
        val chosen = scale?.coerce(effort)
        return if (scale == null || chosen == scale.default) {
            copy(aiEfforts = aiEfforts - provider)
        } else {
            copy(aiEfforts = aiEfforts + (provider to chosen))
        }
    }

    /**
     * Whether lines from [source] may be used: its switch is on, or it is the player's metadata.
     * Web search also needs an engine switched on.
     */
    fun isEnabled(source: SourceId): Boolean =
        when (val switch = SourceSwitch.of(source)) {
            null -> true
            SourceSwitch.WEB -> switch in enabledSources && webEngines.isNotEmpty()
            else -> switch in enabledSources
        }

    /**
     * Whether [source]'s tags give genre labels: [showGenres] is on, its label switch is on, and its
     * source is on under *Sources*, since the tags come with that source's lookup. Last.fm also
     * needs its key, without which it is not looked up at all.
     */
    fun givesLabels(source: LabelSource): Boolean = showGenres && source in labelSources && isEnabled(source.source)

    /**
     * These settings with developer mode off, and with it every developer option ([showDiagnostics]),
     * so nothing hidden keeps running. A developer option joins here with its setting.
     */
    fun withoutDeveloperMode(): Settings = copy(developerMode = false, showDiagnostics = false)

    companion object {
        const val SPOTIFY: String = "com.spotify.music"

        /**
         * The packages whose media sessions are phone calls (#435): Android's telecom service, the
         * telephony process, and the dialers and in-call screens of AOSP, Google and Samsung. A
         * call's session may carry the caller's name or number as its title, which must never be
         * looked up or sent to an AI service, so these are never listed, recorded or followed,
         * whatever a settings file says. Not a setting: calls are not what the app is for. A call
         * from another app (a VoIP app) is told by its session instead (the session layer's
         * `PlayerSession.marksCall`).
         */
        val CALL_PACKAGES: Set<String> =
            setOf(
                "com.android.server.telecom",
                "com.android.phone",
                "com.android.dialer",
                "com.android.incallui",
                "com.google.android.dialer",
                "com.samsung.android.dialer",
                "com.samsung.android.incallui",
            )

        /** Whether [player] (a package name) is one whose sessions are phone calls: [CALL_PACKAGES]. */
        fun isCall(player: String): Boolean = player in CALL_PACKAGES

        /**
         * Each provider's default model: its cheapest current text model. The key and its bill are
         * the user's (driver 5), and an insight narrates a few evidence lines, which needs no larger
         * model; the user can choose another from the provider's list. The other providers get
         * theirs with their clients.
         *
         * - Anthropic: Claude Haiku 5.5, the cheapest current model in the Anthropic models API on
         *   2026-10-14.
         * - OpenAI: GPT-6 Luna (`gpt-6-luna`, $0.10 and $0.50 per million input and output tokens),
         *   the cheapest of OpenAI's current models on its models and pricing pages on 2026-10-21.
         *   GPT-5 nano is cheaper but shuts down on 2026-12-11, as do the older nano models before
         *   it; GPT-6 Luna is named as their successor.
         * - Google: Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`, $0.30 and $2.50 per million input
         *   and output tokens), the cheapest stable text model in the Gemini API's models and pricing
         *   pages on 2026-10-21 that is not deprecated. Gemini 3.1 Flash-Lite costs less, but is
         *   deprecated, to shut down on 2027-05-07, with 3.5 Flash-Lite its replacement; the cheaper
         *   2.5 models are open only to projects that used them before.
         */
        val DEFAULT_MODELS: Map<ProviderId, String> =
            mapOf(
                ProviderId.ANTHROPIC to "claude-haiku-5-5",
                ProviderId.OPENAI to "gpt-6-luna",
                ProviderId.GOOGLE to "gemini-3.5-flash-lite",
            )

        /**
         * Each provider's reasoning-effort scale, with its default; a provider with none sends no
         * effort. Providers do not share one scale, so each lists its own levels, as its service
         * names them, and the settings offer only those.
         *
         * Anthropic: low, medium and high (`output_config.effort`), low by default. An insight
         * narrates a few evidence lines in two to four sentences: it needs little reasoning, and it
         * is waited for on the now-playing screen, so latency matters more than depth (Claude Haiku
         * 5.5 reasons by default, at medium effort). What keeps the text honest is the grounding
         * check, not the model's deliberation. Thinking is not switched off: not every model allows
         * it (Claude Opus 5.5 refuses), while every current one takes an effort. A user who prefers
         * more deliberate cards raises it, or chooses the provider's default.
         *
         * OpenAI: low, medium and high (`reasoning.effort`), low by default, for the same reasons
         * (GPT-6 Luna reasons at medium by default). Its models also name `none`, `minimal`,
         * `xhigh` and `max`, but not all of them take each: GPT-6 Astra and 6.1 Sol refuse `none`,
         * only GPT-5 takes `minimal`, and `xhigh` and `max` buy depth a card of a few sentences
         * does not use. Low, medium and high are the levels every current reasoning model takes.
         *
         * Google: minimal, low, medium and high (`thinkingConfig.thinkingLevel`), low by default, for
         * the same reasons. Low rather than minimal (Gemini 3.5 Flash-Lite's own default): low is the
         * least level every current Gemini text model takes (Gemini 3.8 Flash and Gemini 4 Argon
         * refuse minimal), so the default means the same whichever model is chosen, and it matches
         * Anthropic's and OpenAI's, so cards from the services are compared at one effort. A user
         * who wants the fastest cards chooses minimal.
         */
        val EFFORT_SCALES: Map<ProviderId, EffortScale> =
            mapOf(
                ProviderId.ANTHROPIC to
                    EffortScale(
                        levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                        default = ReasoningEffort.LOW,
                    ),
                ProviderId.OPENAI to
                    EffortScale(
                        levels = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                        default = ReasoningEffort.LOW,
                    ),
                ProviderId.GOOGLE to
                    EffortScale(
                        levels =
                            listOf(
                                ReasoningEffort.MINIMAL,
                                ReasoningEffort.LOW,
                                ReasoningEffort.MEDIUM,
                                ReasoningEffort.HIGH,
                            ),
                        default = ReasoningEffort.LOW,
                    ),
            )

        /** All on except the model's own knowledge. */
        val DEFAULT_SOURCES: Set<SourceSwitch> = SourceSwitch.entries.toSet() - SourceSwitch.MODEL

        /**
         * Every engine on. Each needs the user's own key, so an engine searches only once its key
         * is saved: the switch is for turning off one whose key is saved, without removing the key.
         */
        val DEFAULT_WEB_ENGINES: Set<WebEngine> = WebEngine.entries.toSet()

        /**
         * Both on (#84). MusicBrainz needs no key, and a new user has no Last.fm key: without
         * MusicBrainz's tags no track gets labels until one is saved, with them 29 of the test
         * playlist's 78 did (design, *D12*). Last.fm is looked up only with its key, so its labels
         * come once the key is saved. The MusicBrainz tags are CC BY-NC-SA 3.0, used for labels
         * only and credited (`LabelSource.MUSICBRAINZ`); in the Play release they are off by
         * default unless the MetaBrainz inquiry (#100) is answered and its terms met, with the reason
         * recorded.
         */
        val DEFAULT_LABEL_SOURCES: Set<LabelSource> = LabelSource.entries.toSet()

        private const val STABLE_DAYS = 30
        private const val ARTICLE_DAYS = 7
        private const val WEB_DAYS = 1

        /**
         * D9: MusicBrainz and Wikidata 30 days, Wikipedia and Last.fm 7 days, web search 1 day.
         * Discogs: 30 days, as for MusicBrainz (D9; owner, 2026-10-13).
         */
        val DEFAULT_CACHE_LIFETIMES: Map<SourceId, Duration> =
            mapOf(
                SourceId.MUSICBRAINZ to STABLE_DAYS.days,
                SourceId.WIKIDATA to STABLE_DAYS.days,
                SourceId.DISCOGS to STABLE_DAYS.days,
                SourceId.WIKIPEDIA to ARTICLE_DAYS.days,
                SourceId.LASTFM to ARTICLE_DAYS.days,
                SourceId.WEB to WEB_DAYS.days,
            )

        private const val ARTIST_DAYS = 90

        /**
         * 90 days. An artist's MusicBrainz id is stable (a merged id redirects), so the entry can
         * live long; what changes is MusicBrainz itself, which may add an artist that was missing
         * (`none`) or a namesake that makes one ambiguous. Three times the MusicBrainz retrieval
         * lifetime (D9) picks such changes up within a season, at no cost a listener would notice:
         * the lookup it saves is one MusicBrainz request at one per second.
         */
        val DEFAULT_ARTIST_CACHE_LIFETIME: Duration = ARTIST_DAYS.days

        private const val MEGABYTE = 1_000_000L
        private const val CACHE_MEGABYTES = 50

        /**
         * 50 MB. A track's retrieval cache holds only the evidence lines and outcomes the pipeline
         * uses, about 5 to 15 KB across its sources, and each written card 1 to 3 KB (estimates, to
         * be measured at the Phase 0.5 gate, #46): some 20 KB a track with its insights, so 50 MB
         * holds about 2,500 tracks, and more when only evidence is cached. Retrieval entries expire
         * within 30 days (D9), so evidence alone reaches the limit only for someone playing over 80
         * new tracks a day. Written cards never expire, though: they accumulate until the limit,
         * which every listener reaches in time (at about 2 KB a card, after some 25,000 cards), and
         * from then on the least recently used go first, the cards of tracks not played for longest.
         * 50 MB is small beside the players' own caches, and an evicted card costs a paid call to
         * write again, which is why it is not lower.
         */
        const val DEFAULT_CACHE_SIZE_LIMIT: Long = CACHE_MEGABYTES * MEGABYTE

        /**
         * 5 track changes (#203). Since #190 the first action lasts as long as the process, which
         * the notification listener keeps alive for days, so one tap would keep automatic writing
         * going, a paid call on the user's key per track, long after they last looked: with *Look
         * up in the background* on, for every track played; with it off, for the track playing
         * whenever the app comes back. Five tracks (some 15 to 20 minutes of listening) let
         * writing go on through a short absence, someone controlling playback from the player or
         * a lock screen for a while, and bound what one forgotten tap costs to five unattended
         * calls: five tracks are written while away, and the sixth waits for a tap. The choices
         * offered are [PAUSE_AUTO_WRITE_CHOICES], and *Off*.
         */
        const val DEFAULT_PAUSE_AUTO_WRITE_AFTER: Int = 5

        /**
         * The choices of *Saver*, besides *Off*: 3 (a few minutes away), 5
         * (the default), 10 (an album side or a commute).
         */
        val PAUSE_AUTO_WRITE_CHOICES: List<Int> = listOf(3, 5, 10)

        private const val HISTORY_DAYS = 90

        /**
         * 90 days. The history is the default track list and what skip detection will learn from
         * (D15), from labels that come rarely (a skip made from Sleevesight, a tap on Write), so it
         * wants a season of track changes rather than a week; a season is also enough to look back
         * on. What the limit bounds is the track changes: each keeps 10 to 30 playback states, some
         * 25 MB a year at 100 changes a day (#148), so about 6 MB at that rate. *Forever* is offered.
         */
        val DEFAULT_HISTORY_LIFETIME: Duration = HISTORY_DAYS.days

        private const val SESSION_GAP_MINUTES = 30

        /**
         * 30 minutes. How long a break in listening ends a listening session: longer than a pause
         * to answer the door or change trains, shorter than a meal or the gap between the morning's
         * listening and the evening's.
         */
        val DEFAULT_SESSION_GAP: Duration = SESSION_GAP_MINUTES.minutes
    }
}

/**
 * The user's switches under *Sources* (`design/design.md`, *Settings*), each covering the
 * sources it names. The player's metadata ([SourceId.PLAYER]) has no switch: it is always used.
 */
enum class SourceSwitch(
    val sources: Set<SourceId>,
) {
    MUSICBRAINZ(setOf(SourceId.MUSICBRAINZ)),
    WIKIPEDIA(setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA)),
    LASTFM(setOf(SourceId.LASTFM)),
    DISCOGS(setOf(SourceId.DISCOGS)),
    WEB(setOf(SourceId.WEB)),
    MODEL(setOf(SourceId.MODEL)),
    ;

    companion object {
        /** The switch that covers [source], or null for the player's metadata. */
        fun of(source: SourceId): SourceSwitch? = entries.firstOrNull { source in it.sources }
    }
}

/**
 * The web search engines (D11), each a switch under *Web search*. The list is fixed, not edited by
 * the user: an engine needs an adapter, a filter for its results and its terms settled before it
 * ships (#80 for Exa), so an engine joins the list with its adapter. Exa is the first.
 */
enum class WebEngine {
    EXA,
}

/**
 * *Font* (#377): the typeface the app's text is set in. The diagnostics screen's monospace text
 * stays monospace either way.
 */
enum class FontChoice {
    /**
     * Roboto, bundled with the app, the default: the same type on every phone, in the family the
     * logo and the product page take their faces from (Roboto Flex, Slab and Mono), though not the
     * same faces. Many phones' system font is not Roboto (Samsung's One UI has its own face, and
     * users can pick another), so without it the app would look different from phone to phone,
     * and in the store screenshots from what a user then sees.
     */
    APP_FONT,

    /** The font chosen in the phone's own settings, for someone who prefers it everywhere. */
    SYSTEM_FONT,
}

/** *Theme* (#444): whether the app is light or dark. */
enum class ThemeChoice {
    /** Light or dark as the phone's dark theme is, the default. */
    SYSTEM,
    LIGHT,
    DARK,
}

/** How the detail content is shown. */
enum class Layout {
    SEPARATE_PAGES,
    EXPANDABLE_BOXES,
}

/** *Reactive* (D5): which track changes write an insight without a tap. */
enum class WriteAutomatically {
    ON_EVERY_TRACK_CHANGE,
    WHEN_I_SKIP,
    NEVER,
}

/**
 * *Proactive* (D13): what runs ahead for the next item in the player's queue.
 * *Evidence and insight*, first proposed as the default, is not offered: the queue item the app
 * reads has no album or length, so an insight written from it would be kept under a key the
 * playing track never reads, and paid for again when it plays (#172 measured Spotify's: they
 * carry neither). A value stored under that name reads as the default, *Evidence*, as any name
 * this version does not know.
 */
enum class PrepareNextTrack {
    OFF,

    /** The resolution by name and MusicBrainz's genre labels, ahead; no paid call. */
    EVIDENCE,
}

/** *Crossfade* (D5), for telling a skip in the player from a natural end. */
sealed interface Crossfade {
    /** Learned from the player's behaviour. */
    data object Autodetect : Crossfade

    /** A fixed crossfade, from 0 to 15 seconds. */
    data class Fixed(
        val value: Duration,
    ) : Crossfade {
        init {
            require(value in Duration.ZERO..MAX) { "Crossfade must be 0 to 15 s, was $value" }
        }

        companion object {
            val MAX: Duration = 15.seconds
        }
    }
}
