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
 * @property followedPlayers one switch per player seen, by package name; see [follows].
 * @property enabledSources the sources the model may use (*Where to look*). The user's Wikipedia
 *   switch covers [SourceId.WIKIPEDIA] and [SourceId.WIKIDATA]; [SourceId.PLAYER] is no switch.
 * @property writeInsights the master switch: off means no AI calls at all; evidence is still shown.
 * @property cacheLifetimes the retrieval cache lifetime per source (D9).
 */
data class Settings(
    val layout: Layout = Layout.SEPARATE_PAGES,
    val aiProvider: ProviderId? = null,
    val aiModel: String? = null,
    val followedPlayers: Map<String, Boolean> = mapOf(SPOTIFY to true),
    val enabledSources: Set<SourceId> = DEFAULT_SOURCES,
    val writeInsights: Boolean = true,
    val writeAutomatically: WriteAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE,
    val crossfade: Crossfade = Crossfade.Autodetect,
    val prepareNextTrack: PrepareNextTrack = PrepareNextTrack.EVIDENCE_AND_INSIGHT,
    val lookUpInBackground: Boolean = false,
    val cacheLifetimes: Map<SourceId, Duration> = DEFAULT_CACHE_LIFETIMES,
) {
    /** Whether events from [player] (a package name) are followed; a player not yet seen is not. */
    fun follows(player: String): Boolean = followedPlayers[player] ?: false

    companion object {
        const val SPOTIFY: String = "com.spotify.music"

        /** All on except the model's own knowledge. */
        val DEFAULT_SOURCES: Set<SourceId> =
            setOf(
                SourceId.MUSICBRAINZ,
                SourceId.WIKIPEDIA,
                SourceId.WIKIDATA,
                SourceId.LASTFM,
                SourceId.DISCOGS,
                SourceId.WEB,
            )

        private const val STABLE_DAYS = 30
        private const val ARTICLE_DAYS = 7
        private const val WEB_DAYS = 1

        /**
         * D9: MusicBrainz and Wikidata 30 days, Wikipedia and Last.fm 7 days, web search 1 day.
         * D9 names no lifetime for Discogs; its release data is as stable as MusicBrainz's, so 30.
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
