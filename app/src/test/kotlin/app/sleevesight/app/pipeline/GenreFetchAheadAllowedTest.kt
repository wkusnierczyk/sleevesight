package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fetch ahead of genres' definitions (#786) only while lookups are allowed (#961): off screen
 * with *Look up in the background* off, a play whose genres come from the cache asks Wikipedia
 * nothing; back on screen, the track then playing is fetched ahead.
 */
class GenreFetchAheadAllowedTest {
    private val asked = mutableListOf<String>()
    private val articles =
        GenreArticles { genre ->
            asked += genre
            Lookup.Found(GenreDefinition("Rock music is a genre of popular music.", "Rock music", "https://w/Rock"))
        }
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val settings = MutableStateFlow<Settings?>(Settings())

    // The MusicBrainz genres Wikipedia is asked about for [genres], by their identities.
    private fun ids(vararg genres: String) = genres.map { checkNotNull(vocabulary.musicBrainzGenre(it)) }

    // A track whose artist's genres are [genres].
    private fun playing(vararg genres: String): TrackState {
        val labels =
            genres.map { tag ->
                TrackLabel(
                    GenreLabel(tag, "rock", tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
                    listOf(LabelCredit(LabelSource.LASTFM, null)),
                )
            }
        return HomeFixtures.track().copy(genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "A", labels))))
    }

    @Test
    fun `off screen with background lookups off, a play asks no definition, back on screen it does (#961)`() =
        runTest {
            val lookups = AppGenreDefinitions(GenreDefinitions(articles, vocabulary), settings, this)
            val tracks = MutableStateFlow<TrackState?>(playing("rock"))
            val allowed = MutableStateFlow(true)
            val following = launch { lookups.fetchAhead(tracks, allowed) }
            advanceUntilIdle()
            assertEquals(ids("rock"), asked)
            // Off screen: a track whose genres the pipeline published from the cache plays.
            allowed.value = false
            tracks.value = playing("punk")
            advanceUntilIdle()
            assertEquals(ids("rock"), asked)
            // Back on screen: the track then playing is fetched ahead, once a play.
            allowed.value = true
            advanceUntilIdle()
            assertEquals(ids("rock", "punk"), asked)
            allowed.value = false
            advanceUntilIdle()
            allowed.value = true
            advanceUntilIdle()
            following.cancel()
            assertEquals(ids("rock", "punk"), asked)
        }
}
