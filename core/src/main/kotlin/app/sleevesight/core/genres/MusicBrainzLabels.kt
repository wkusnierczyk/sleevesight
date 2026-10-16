package app.sleevesight.core.genres

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.Resolution

/**
 * An artist's MusicBrainz tags (`inc=tags` on the artist), most voted first: `MusicBrainzClient`
 * in `:sources`, a fake in tests. Kept apart from [app.sleevesight.core.resolution.MusicBrainzLookup],
 * which asks for core data only: tags are CC BY-NC-SA 3.0, used for genre labels only and never
 * sent in a prompt (`design/architecture.md`, *MusicBrainz tags and genres (#9)*, *Conditions*).
 */
fun interface ArtistTags {
    suspend fun artistTags(mbid: String): Lookup<List<String>>
}

/**
 * The genre labels from the MusicBrainz tags of a track's artists (D12, revised; #84): each
 * resolved artist's tags, in credit order, through the [vocabulary], at most
 * [GenreVocabulary.MAX_LABELS], each genre once. Only artists the resolution gave a MusicBrainz id
 * are asked, so a label is never from a namesake; each label links its artist's MusicBrainz page.
 */
class MusicBrainzLabels(
    private val tags: ArtistTags,
    private val vocabulary: GenreVocabulary,
) {
    /** The labels, and whether every artist's tags were obtained ([complete]): false when a request failed. */
    data class Found(
        val labels: List<GenreLabel>,
        val complete: Boolean,
    )

    suspend fun labels(resolution: Resolution): Found {
        var complete = true
        val labels =
            resolution.artists.mapNotNull { it.mbid }.distinct().flatMap { mbid ->
                when (val found = tags.artistTags(mbid)) {
                    is Lookup.Found -> {
                        vocabulary.labelsFor(found.value, artistPage(mbid))
                    }

                    Lookup.NotFound -> {
                        emptyList()
                    }

                    is Lookup.Failed -> {
                        complete = false
                        emptyList()
                    }
                }
            }
        return Found(labels.distinctBy { it.identity }.take(GenreVocabulary.MAX_LABELS), complete)
    }

    companion object {
        /** The artist's page on MusicBrainz, which a label from its tags links. */
        fun artistPage(mbid: String): String = "https://musicbrainz.org/artist/$mbid"
    }
}
