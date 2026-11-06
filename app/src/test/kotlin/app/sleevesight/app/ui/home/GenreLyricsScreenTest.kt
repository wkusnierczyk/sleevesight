package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.LyricsSite
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Lyrics found keep Instrumental out of *Genres* (#996): a page *Find the lyrics* found drops its
 * chip, for the track it was found for only; and *Search with AI*'s genres, shown with no source's
 * genre, leave it out once lyrics are found. The AI's Instrumental on a sung track is weak, with its
 * line, among the rows' chips and among *Search with AI*'s.
 */
@RunWith(RobolectricTestRunner::class)
class GenreLyricsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun genre(
        tag: String,
        level: GenreLevel,
        artist: String? = null,
    ) = GenreLabel(tag, "family", tag.replaceFirstChar(Char::uppercase), tag, level = level, artist = artist)

    // Artist A tagged Instrumental and Funk.
    private val tagged =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.ARTIST,
                    "A",
                    listOf("instrumental", "funk").map {
                        TrackLabel(
                            genre(it, GenreLevel.ARTIST, "A"),
                            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$it")),
                        )
                    },
                ),
            ),
            credited = listOf("A"),
        )

    private val found =
        AiSearch.Answered(
            SearchAnswer.Lyrics(listOf(LyricsLink(LyricsSite.GENIUS, "https://genius.com/A-song-lyrics"))),
            "Model",
        )

    // A feed whose searches [answer] gives, by track and kind; it asks nothing.
    private fun feed(answer: (TrackState, SearchKind) -> Flow<AiSearch>) =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = answer(track, kind)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ) = false

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    // *Genres*' panel for the track [playing] holds, its searches from [feed].
    private fun show(
        playing: MutableState<TrackState>,
        feed: AiSearchFeed,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val now = playing.value
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(now, Settings(), search = PanelSearch(feed, now, now.nowPlaying))
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes().size

    // The weak genre's line, once its chip, tagged [chip], is tapped.
    private fun lineUnder(chip: String): String {
        compose.onNodeWithTag(chip).performClick()
        compose.waitForIdle()
        return compose
            .onNodeWithTag(GENRE_WEAK_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
            .text
    }

    @Test
    fun theAisInstrumentalOnASungTrackIsWeakAmongTheRowsChipsWithItsLine() {
        val listed = listOf(genre("instrumental", GenreLevel.TRACK), genre("blues", GenreLevel.TRACK))
        // Artist A's Funk; the AI's list adds Instrumental and Blues to the track's row.
        val funk = tagged.copy(rows = tagged.rows.map { row -> row.copy(labels = row.labels.drop(1)) })
        val sung = funk.copy(singing = Singing(languages = listOf("jpn"))).withAi(listed, "Model")
        show(mutableStateOf(track().copy(genres = sung)), feed { _, _ -> flowOf(AiSearch.None) }) {
            assertEquals(1, described("Instrumental, from Model, weak evidence"))
            assertEquals(1, described("Blues, from Model"))
            assertEquals(
                "MusicBrainz lists this song’s lyrics as Japanese, so it is likely not Instrumental.",
                lineUnder(GENRE_PREFIX + "track:instrumental"),
            )
        }
    }

    @Test
    fun searchWithAisInstrumentalOnASungTrackIsWeakWithItsLine() {
        val listed =
            AiSearch.Answered(
                SearchAnswer.Genres(listOf(genre("instrumental", GenreLevel.TRACK), genre("blues", GenreLevel.TRACK))),
                "Model",
            )
        val genres = feed { _, kind -> flowOf(if (kind == SearchKind.GENRES) listed else AiSearch.None) }
        val sung = TrackGenres(singing = Singing(vocalists = listOf("Yurika Hanashima")))
        show(mutableStateOf(track().copy(genres = sung, genresAssembled = true)), genres) {
            assertEquals(1, described("Instrumental, from Model, weak evidence"))
            assertEquals(1, described("Blues, from Model"))
            assertEquals(
                "MusicBrainz credits Yurika Hanashima with vocals on this track, most likely it is not Instrumental.",
                lineUnder(GENRE_PREFIX + "track:instrumental"),
            )
        }
    }

    @Test
    fun aPageFindTheLyricsFoundDropsInstrumentalsChip() {
        val lyrics = feed { _, kind -> flowOf(if (kind == SearchKind.LYRICS) found else AiSearch.None) }
        show(mutableStateOf(track().copy(genres = tagged)), lyrics) {
            assertEquals(0, count(INSTRUMENTAL))
            assertEquals(1, count(GENRE_PREFIX + "artist:A:funk"))
        }
        // Without the answer, the chip is there.
        show(mutableStateOf(track().copy(genres = tagged)), feed { _, _ -> flowOf(AiSearch.None) }) {
            assertEquals(1, count(INSTRUMENTAL))
        }
    }

    @Test
    fun theTrackBeforesLyricsDoNotStandForTheNextTracksWhileItsSearchHasNotAnswered() {
        val first = track().copy(genres = tagged)
        val next = track(playing = first.nowPlaying.copy(title = "Carefree Highway")).copy(genres = tagged)
        // The next track's search answers nothing yet.
        val lyrics =
            feed { track, kind ->
                when {
                    kind != SearchKind.LYRICS -> flowOf(AiSearch.None)
                    track.nowPlaying.title == first.nowPlaying.title -> flowOf(found)
                    else -> flow { awaitCancellation() }
                }
            }
        val playing = mutableStateOf(first)
        show(playing, lyrics) {
            assertEquals(0, count(INSTRUMENTAL))
            playing.value = next
            compose.waitForIdle()
            assertEquals(1, count(INSTRUMENTAL))
        }
    }

    @Test
    fun searchWithAisGenresLeaveInstrumentalOutOnceLyricsAreFound() {
        val listed =
            AiSearch.Answered(
                SearchAnswer.Genres(listOf(genre("instrumental", GenreLevel.TRACK), genre("blues", GenreLevel.TRACK))),
                "Model",
            )
        val genres = feed { _, kind -> flowOf(if (kind == SearchKind.GENRES) listed else AiSearch.None) }
        // No source's genre, the track's work with lyrics links.
        val empty = TrackGenres(singing = Singing(lyrics = true))
        show(mutableStateOf(track().copy(genres = empty, genresAssembled = true)), genres) {
            assertEquals(0, count(GENRE_PREFIX + "track:instrumental"))
            assertEquals(1, count(GENRE_PREFIX + "track:blues"))
        }
        // Without lyrics, both.
        show(mutableStateOf(track().copy(genresAssembled = true)), genres) {
            assertEquals(1, count(GENRE_PREFIX + "track:instrumental"))
        }
    }

    private companion object {
        const val INSTRUMENTAL = GENRE_PREFIX + "artist:A:instrumental"
    }
}
