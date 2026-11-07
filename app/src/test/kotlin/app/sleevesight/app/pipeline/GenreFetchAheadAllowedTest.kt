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
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // The genres Wikipedia is asked about, and those whose next lookup fails (a failure is not kept,
    // so a genre's next play asks Wikipedia again).
    private val asked = mutableListOf<String>()
    private val failing = mutableSetOf<String>()
    private val articles =
        GenreArticles { genre ->
            asked += genre
            if (failing.remove(genre)) {
                Lookup.Failed("offline")
            } else {
                Lookup.Found(GenreDefinition("Rock music is a genre of popular music.", "Rock music", "https://w/Rock"))
            }
        }
    private val settings = MutableStateFlow<Settings?>(Settings())

    // The MusicBrainz genres Wikipedia is asked about for [genres], by their identities.
    private fun ids(vararg genres: String) = genres.map { checkNotNull(vocabulary.musicBrainzGenre(it)) }

    // A track whose artist's genres are [genres], titled after them, so each is a play of its own.
    private fun playing(vararg genres: String): TrackState {
        val labels =
            genres.map { tag ->
                TrackLabel(
                    GenreLabel(tag, tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
                    listOf(LabelCredit(LabelSource.LASTFM, null)),
                )
            }
        val track = HomeFixtures.track()
        return track.copy(
            normalized = track.normalized.copy(title = genres.joinToString()),
            genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "A", labels))),
        )
    }

    @Test
    fun `off screen with background lookups off, a play asks no definition, back on screen it does (#961)`() =
        runTest {
            val lookups = AppGenreDefinitions(GenreDefinitions(articles, vocabulary), settings, this)
            val tracks = MutableStateFlow<TrackState?>(playing("rock"))
            val allowed = MutableStateFlow(true)
            failing += ids("punk")
            val following = launch { lookups.fetchAhead(tracks, allowed) }
            advanceUntilIdle()
            assertEquals(ids("rock"), asked)
            // Off screen: a track whose genres the pipeline published from the cache plays.
            allowed.value = false
            tracks.value = playing("punk")
            advanceUntilIdle()
            assertEquals(ids("rock"), asked)
            // Back on screen: the track then playing is fetched ahead, once a play (its lookup fails).
            allowed.value = true
            advanceUntilIdle()
            assertEquals(ids("rock", "punk"), asked)
            allowed.value = false
            advanceUntilIdle()
            allowed.value = true
            advanceUntilIdle()
            assertEquals(ids("rock", "punk"), asked)
            // Off screen, another track and then this one again: back on screen, a new play, asked again.
            allowed.value = false
            tracks.value = playing("rock")
            advanceUntilIdle()
            tracks.value = playing("punk")
            advanceUntilIdle()
            allowed.value = true
            advanceUntilIdle()
            following.cancel()
            assertEquals(ids("rock", "punk", "punk"), asked)
        }

    @Test
    fun `not allowed from the start, settings coming on ask nothing until lookups are allowed (#961)`() =
        runTest {
            val on = checkNotNull(settings.value)
            settings.value = on.copy(showGenres = false)
            val lookups = AppGenreDefinitions(GenreDefinitions(articles, vocabulary), settings, this)
            val allowed = MutableStateFlow(false)
            val following = launch { lookups.fetchAhead(MutableStateFlow(playing("rock")), allowed) }
            advanceUntilIdle()
            settings.value = on
            advanceUntilIdle()
            assertEquals(emptyList<String>(), asked)
            allowed.value = true
            advanceUntilIdle()
            following.cancel()
            assertEquals(ids("rock"), asked)
        }
}
