package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
import kotlin.time.Duration

/**
 * What a card is about (`design/architecture.md`, *Cache and history*), part of its [CardKey]: the
 * track for every kind but the artist and album cards ([CardKind][app.sleevesight.core.synthesis.CardKind]
 * `ARTIST` and `ALBUM`), which are about the artist or the album whichever of its tracks is playing,
 * so an album's tracks share them rather than paying for them again on each (#240). The store turns
 * each into its own entity key.
 */
sealed interface CardEntity {
    /**
     * A [track], played at [duration] (the player's length, part of its key), and what the card is
     * about besides the track, [subject], null for none: for a genre's reason
     * ([CardKind.GENRE][app.sleevesight.core.synthesis.CardKind.GENRE], #523), the genre's identity
     * (`GenreLabel.identity`), so each genre of a track keeps its own.
     */
    data class Track(
        val track: NormalizedTrack,
        val duration: Duration?,
        val subject: String? = null,
    ) : CardEntity

    /**
     * An artist: by its MusicBrainz id, [mbid], when the resolution gave one; else by its [name] as
     * credited, folded as the retrieval key folds names. A namesake keyed by the same name shares the
     * key, but not the card unless the evidence is the same too: the evidence fingerprint is part of
     * the key.
     */
    data class Artist(
        val name: String,
        val mbid: String?,
    ) : CardEntity {
        companion object {
            /** The artist [artist] credits. */
            fun of(artist: ResolvedArtist): Artist = Artist(artist.name, artist.mbid)
        }
    }

    /**
     * An album: by its MusicBrainz release group, [group] (the release group's id, else the release's
     * own, as [ReleaseDate.group] has it), when the album's name names one; else by its [title] and
     * the track's primary [artist], each folded as the retrieval key folds names, so two artists'
     * albums of one name stay apart.
     */
    data class Album(
        val title: String,
        val artist: String,
        val group: String?,
    ) : CardEntity {
        companion object {
            /**
             * The album [title], the player's name for it, of a track whose primary artist is
             * [artist]: its release group is the one group of the recording's releases [dates] that
             * [title] names, as [key] reads names ([ReleaseDate.named], the releases the year after
             * the album comes from), or none when it names none or several (two compilations of one
             * name), where nothing tells which is playing.
             */
            fun of(
                title: String,
                artist: String,
                dates: List<ReleaseDate>,
                key: (String) -> String,
            ): Album {
                val groups = ReleaseDate.named(title, dates, key).map { it.group }.distinct()
                return Album(title, artist, groups.singleOrNull())
            }
        }
    }
}
