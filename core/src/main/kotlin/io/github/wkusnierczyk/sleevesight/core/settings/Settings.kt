package io.github.wkusnierczyk.sleevesight.core.settings

import io.github.wkusnierczyk.sleevesight.core.model.ProviderId
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The user's settings, with their defaults (`design/design.md`, *Settings*; architecture,
 * driver 6). `:app` reads them from DataStore into this type, and `:cli` uses the defaults, so the
 * two run the pipeline the same way. Secrets (API keys, tokens) are not settings.
 *
 * @property aiModel the model the user chose for [aiProvider], or null for its default; read [model].
 * @property followedPlayers one switch per player seen, by package name; see [follows].
 * @property enabledSources the user's *Where to look* switches: what the model may use. Ask
 *   [isEnabled] about a [SourceId], which maps it to its switch.
 * @property writeInsights the master switch: off means no AI calls at all; evidence is still shown.
 * @property cacheLifetimes the retrieval cache lifetime per source (D9). A source with none (the
 *   pseudo-sources, which retrieve nothing) is not cached.
 * @property artistCacheLifetime how long a credited artist's MusicBrainz id, or the finding that it
 *   has none or several, is kept (architecture, *Cache and history*: "long, months").
 * @property cacheSizeLimit the most the caches may hold, in bytes of keys and values; past it the
 *   least recently used entries are evicted. See [DEFAULT_CACHE_SIZE_LIMIT].
 */
data class Settings(
    val layout: Layout = Layout.SEPARATE_PAGES,
    val aiProvider: ProviderId? = null,
    val aiModel: String? = null,
    val followedPlayers: Map<String, Boolean> = mapOf(SPOTIFY to true),
    val enabledSources: Set<SourceSwitch> = DEFAULT_SOURCES,
    val writeInsights: Boolean = true,
    val writeAutomatically: WriteAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE,
    val crossfade: Crossfade = Crossfade.Autodetect,
    val prepareNextTrack: PrepareNextTrack = PrepareNextTrack.EVIDENCE_AND_INSIGHT,
    val lookUpInBackground: Boolean = false,
    val cacheLifetimes: Map<SourceId, Duration> = DEFAULT_CACHE_LIFETIMES,
    val artistCacheLifetime: Duration = DEFAULT_ARTIST_CACHE_LIFETIME,
    val cacheSizeLimit: Long = DEFAULT_CACHE_SIZE_LIMIT,
) {
    init {
        require(cacheSizeLimit >= 0) { "The cache size limit must not be negative, was $cacheSizeLimit" }
    }

    /** Whether events from [player] (a package name) are followed; a player not yet seen is not. */
    fun follows(player: String): Boolean = followedPlayers[player] ?: false

    /** The model to write with: the user's choice, or [aiProvider]'s default ([DEFAULT_MODELS]), or none. */
    val model: String? get() = aiModel ?: aiProvider?.let { DEFAULT_MODELS[it] }

    /** Whether lines from [source] may be used: its switch is on, or it is the player's metadata. */
    fun isEnabled(source: SourceId): Boolean = SourceSwitch.of(source)?.let { it in enabledSources } ?: true

    companion object {
        const val SPOTIFY: String = "com.spotify.music"

        /**
         * Each provider's default model. Anthropic: Claude Haiku 5.5, the cheapest current model in
         * the Anthropic models API on 2026-10-14. The key and its bill are the user's (driver 5), and
         * an insight narrates a few evidence lines, which needs no larger model; the user can choose
         * another from the provider's list. The other providers get theirs with their clients.
         */
        val DEFAULT_MODELS: Map<ProviderId, String> = mapOf(ProviderId.ANTHROPIC to "claude-haiku-5-5")

        /** All on except the model's own knowledge. */
        val DEFAULT_SOURCES: Set<SourceSwitch> = SourceSwitch.entries.toSet() - SourceSwitch.MODEL

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
         * holds about 2,500 tracks, and more when only evidence is cached. Since retrieval entries
         * expire within 30 days (D9), the limit binds only for someone who plays over 80 new tracks
         * a day; below that, expiry keeps the caches smaller. 50 MB is small beside the players'
         * own caches, and an evicted entry costs a refetch, or a paid call for an insight, which is
         * why it is not lower.
         */
        const val DEFAULT_CACHE_SIZE_LIMIT: Long = CACHE_MEGABYTES * MEGABYTE
    }
}

/**
 * The user's switches under *Where to look* (`design/design.md`, *Settings*), each covering the
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

/** How the detail content is shown. */
enum class Layout {
    SEPARATE_PAGES,
    EXPANDABLE_BOXES,
}

/** *Write insights automatically* (D5): which track changes write an insight without a tap. */
enum class WriteAutomatically {
    ON_EVERY_TRACK_CHANGE,
    WHEN_I_SKIP,
    NEVER,
}

/** *Prepare the next track* (D13): what runs ahead for the next item in the player's queue. */
enum class PrepareNextTrack {
    OFF,
    EVIDENCE,
    EVIDENCE_AND_INSIGHT,
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
