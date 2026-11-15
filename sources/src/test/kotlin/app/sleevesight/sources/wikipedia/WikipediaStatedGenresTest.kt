package app.sleevesight.sources.wikipedia

import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.StatedGenre
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The genres Wikidata states for the song's and the artists' items (#1098), on Wikipedia's result
 * (`SourceResult.statedGenres`), from the items its identity check accepted, with no request of
 * their own; an artist's kept in the artist cache with its lines.
 */
class WikipediaStatedGenresTest {
    // The song MusicBrainz links (Q1) and the artist's item (Q2), with the genres they state.
    private class Wikidata(
        val song: Map<String, List<String>>,
    ) : WikidataLookup {
        var entityRequests = 0

        private val artist =
            mapOf(Wd.MUSICBRAINZ_ARTIST to listOf("mb-h"), Wd.GENRE to listOf("Q-soul", "Q-pop"))

        override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> {
            entityRequests++
            return Lookup.Found(
                ids.associateWith {
                    if (it ==
                        "Q2"
                    ) {
                        WdEntity(it, "Hall & Oates", null, artist)
                    } else {
                        WdEntity(it, "I Can't Go for That", null, song)
                    }
                },
            )
        }

        override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
            Lookup.Found(LABELS.filterKeys { it in ids })

        override suspend fun claimValues(
            id: String,
            property: String,
        ): Lookup<List<String>> = Lookup.Found(if (id == "Q2") artist[property].orEmpty() else song[property].orEmpty())

        override suspend fun itemsWithStatement(
            property: String,
            value: String,
        ): Lookup<List<String>> =
            Lookup.Found(
                if (property == Wd.MUSICBRAINZ_ARTIST &&
                    value == "mb-h"
                ) {
                    listOf("Q2")
                } else {
                    emptyList()
                },
            )
    }

    private val track = RecordedTracks.normalize("I Can't Go for That (No Can Do)", "Daryl Hall & John Oates")

    private val resolution =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "mb-r",
            artists = listOf(ResolvedArtist("Daryl Hall & John Oates", "mb-h")),
            wikidataIds = listOf("Q1"),
        )

    private val song = mapOf(Wd.INSTANCE_OF to listOf("Q7366"), Wd.GENRE to listOf("Q-bes"))

    private suspend fun stated(
        wikidata: Wikidata,
        genres: Boolean = true,
        artists: ArtistResults = ArtistResults.None,
    ): List<StatedGenre> {
        val adapter =
            WikipediaSource(
                WikipediaClient(ReplayNetClient.ok("""{"query":{"pages":[]}}""")),
                IdentityCheck(wikidata, RecordedTracks.names, genres),
                RecordedTracks.names,
                artists = artists,
            )
        return adapter.lookup(track, CompletableDeferred(resolution)).statedGenres
    }

    @Test
    fun `the song's genres are the track's and the artist's the artist's, each linking its item`() =
        runTest {
            assertEquals(
                listOf(
                    StatedGenre("blue-eyed soul", GenreLevel.TRACK, null, Wd.url("Q1")),
                    StatedGenre("soul music", GenreLevel.ARTIST, "Daryl Hall & John Oates", Wd.url("Q2")),
                    StatedGenre("pop music", GenreLevel.ARTIST, "Daryl Hall & John Oates", Wd.url("Q2")),
                ),
                stated(Wikidata(song)),
            )
        }

    @Test
    fun `with genres off, the check drops them and none are stated`() =
        runTest {
            assertEquals(emptyList(), stated(Wikidata(song), genres = false))
        }

    @Test
    fun `an original song's genres are not the cover's, so none is the track's`() =
        runTest {
            // The item MusicBrainz links names another performer: the original song, whose genres the
            // cover may not share. The artist's still are.
            val original = song + (Wd.PERFORMER to listOf("Q-someone"))
            assertEquals(
                listOf(GenreLevel.ARTIST, GenreLevel.ARTIST),
                stated(Wikidata(original)).map { it.level },
            )
        }

    @Test
    fun `an artist's genres are kept with its article, so the next track asks nothing for them`() =
        runTest {
            val kept = MemoryArtistResults()
            stated(Wikidata(song), artists = kept)
            val again = Wikidata(song)
            val genres = stated(again, artists = kept)
            assertEquals(
                listOf("soul music", "pop music"),
                genres.filter { it.level == GenreLevel.ARTIST }.map { it.name },
            )
            // The song's item only: the artist's came from the cache.
            assertEquals(1, again.entityRequests)
        }

    private companion object {
        val LABELS = mapOf("Q-bes" to "blue-eyed soul", "Q-soul" to "soul music", "Q-pop" to "pop music")
    }
}
