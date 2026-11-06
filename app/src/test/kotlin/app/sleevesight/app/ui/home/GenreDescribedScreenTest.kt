package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The genres an artist's description names, on Home (#1000): their chips after the tags', read "…,
 * from the artist’s description"; a *Described* row after the artist's tags, its sources the
 * description and its citations; under such a genre's definition where it is named, quoted and
 * cited, a link to the line; Instrumental from it weak, with the artist's hint's line in place of
 * that (#998); dropped with lyrics found.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class GenreDescribedScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val at = Instant.parse("2026-11-06T00:00:00Z")

    private val eros =
        NowPlaying(
            title = "Eros",
            artists = ISLANDMAN,
            album = "Island5",
            duration = 4.minutes,
            player = Settings.SPOTIFY,
        )

    private fun draft(
        source: SourceId,
        text: String,
        about: String? = null,
    ) = EvidenceDraft(source, Scope.ARTIST, text, "https://example.org/${source.tag}", at, about = about)

    // The web line about islandman (#1000), the 8th of the web's lines, as *Sources* shows it.
    private val lines =
        EvidenceSet.of(
            (1..7).map { EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it about the track.", null, at) } +
                draft(
                    SourceId.WEB,
                    "Islandman is described as an instrumental electro-acoustic, neo-psychedelic band from " +
                        "Turkey that reimagines the deep-rooted sounds of Anatolia.",
                ),
            JvmHasher,
        )

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    private val tags =
        combineGenres(
            mapOf(LabelSource.LASTFM to artist(ISLANDMAN, "ambient", "electronic", "house")),
            listOf(ISLANDMAN),
        )

    private val cited = mutableListOf<String>()

    // The evidence each reason was subscribed to and asked with, by genre.
    private val subscribed = mutableMapOf<String, EvidenceSet?>()
    private val explained = mutableMapOf<String, EvidenceSet?>()

    private val notes =
        object : GenreNotes {
            override val vocabulary: GenreVocabulary = this@GenreDescribedScreenTest.vocabulary

            override fun definition(genre: String): Flow<DefinitionState?> =
                flowOf(DefinitionState.Found(GenreDefinition("What $genre is.", genre, "https://example.org/$genre")))

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> {
                subscribed[genre] = track.evidence
                return flowOf(GenreReason.Offered)
            }

            override fun explain(
                track: TrackState,
                genre: String,
            ) {
                explained[genre] = track.evidence
            }
        }

    private fun show(
        genres: TrackGenres = tags,
        playing: NowPlaying = eros,
        evidence: EvidenceSet = lines,
        block: () -> Unit,
    ) {
        val shown = PanelNotes(notes, { cited += it }, lines = evidence)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresContent(track(playing).copy(genres = genres), Settings(), shown)
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun description(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

    // The text of the node tagged [tag], joined over its parts.
    private fun textOf(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun chips(): List<String> =
        listOf(
            "artist:$ISLANDMAN:ambient",
            "artist:$ISLANDMAN:electronic",
            "artist:$ISLANDMAN:house",
            "described:$ISLANDMAN:instrumental",
        ).filter { compose.onAllNodesWithTag(GENRE_PREFIX + it).fetchSemanticsNodes().isNotEmpty() }

    private val quote =
        "Islandman is described as an instrumental electro-acoustic, " +
            "neo-psychedelic band from Turkey…"

    @Test
    fun erosShowsAWeakInstrumentalAfterTheTagsWithTheHintQuotedAndCited() =
        show {
            assertEquals(
                listOf(
                    "artist:$ISLANDMAN:ambient",
                    "artist:$ISLANDMAN:electronic",
                    "artist:$ISLANDMAN:house",
                    "described:$ISLANDMAN:instrumental",
                ),
                chips(),
            )
            val instrumental = GENRE_PREFIX + "described:$ISLANDMAN:instrumental"
            // Weak, and read as from the artist's description.
            val spoken = text(R.string.genre_weak, text(R.string.genre_described, "Instrumental"))
            assertEquals("Instrumental, from the artist’s description, weak evidence", spoken)
            assertEquals(spoken, description(instrumental))
            tap(instrumental)
            // The hint's line in place of "Named in…", its citation a link to the line in *Sources*.
            assertEquals(text(R.string.genre_weak_hint, quote) + " S8", textOf(GENRE_WEAK_TAG))
            assertEquals(0, compose.onAllNodesWithTag(GENRE_DESCRIBED_TAG).fetchSemanticsNodes().size)
            compose.onNodeWithTag(GENRE_WEAK_TAG).performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_CITE_PREFIX + "S8"
            }
            assertEquals(listOf("S8"), cited)
            compose.onNodeWithTag(GENRE_WHY_TAG).fetchSemanticsNode()
            // Its own row after the artist's, read "Described, Instrumental, from the artist’s
            // description, selected", its sources the description and the citation.
            assertEquals("Artist, Ambient, Electronic, House", compose.onNodeWithTag(GENRES_ROW_PREFIX + "0").reading())
            assertEquals(
                "Described, Instrumental, from the artist’s description, selected",
                compose.onNodeWithTag(GENRES_ROW_PREFIX + "1").reading(),
            )
            assertEquals("Described, islandman’s description · S8", textOf(GENRE_SOURCES_ROW_PREFIX + "1"))
            compose.onNodeWithTag(GENRE_SOURCES_ROW_PREFIX + "1").performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_CITE_PREFIX + "S8"
            }
            assertEquals(listOf("S8", "S8"), cited)
        }

    @Test
    fun aGenreTheDescriptionNamesIsNamedUnderItsDefinitionQuotedAndCited() {
        val jazz =
            EvidenceSet.of(
                listOf(draft(SourceId.LASTFM, "The Last.fm biography of islandman says: \"They also play jazz.\"")),
                JvmHasher,
            )
        show(evidence = jazz) {
            val chip = GENRE_PREFIX + "described:$ISLANDMAN:jazz"
            assertEquals(text(R.string.genre_described, "Jazz"), description(chip))
            tap(chip)
            assertEquals(
                text(R.string.genre_described_named, "They also play jazz.") + " L1",
                textOf(GENRE_DESCRIBED_TAG),
            )
            val named = "Named in the artist’s description: “They also play jazz.” L1"
            assertEquals(named, textOf(GENRE_DESCRIBED_TAG))
            assertEquals(0, compose.onAllNodesWithTag(GENRE_WEAK_TAG).fetchSemanticsNodes().size)
            // *Why is this …?* after it.
            val note = compose.onNodeWithTag(GENRE_DESCRIBED_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(
                note.bottom <=
                    compose
                        .onNodeWithTag(GENRE_WHY_TAG)
                        .fetchSemanticsNode()
                        .boundsInRoot.top,
            )
        }
    }

    @Test
    fun withSeveralArtistsEachDescribedRowIsNumberedAsItsArtistsAndNamesWhose() {
        val pair = eros.copy(artists = "Ann, Bob")
        val credited = track(pair).normalized.artists
        val (ann, bob) = credited
        val lines =
            EvidenceSet.of(
                listOf(
                    draft(SourceId.LASTFM, "A blues singer.", about = ann),
                    draft(SourceId.LASTFM, "A soul singer.", about = bob),
                ),
                JvmHasher,
            )
        val genres = combineGenres(mapOf(LabelSource.LASTFM to artist(ann, "rock") + artist(bob, "pop")), credited)
        show(genres, pair, lines) {
            assertEquals(
                listOf(
                    "Artist 1, Rock",
                    "Described 1, Blues, from the artist’s description",
                    "Artist 2, Pop",
                    "Described 2, Soul, from the artist’s description",
                ),
                (0..3).map { compose.onNodeWithTag(GENRES_ROW_PREFIX + it).reading()?.replace(", selected", "") },
            )
            assertEquals("Described 1, $ann’s description · L1", textOf(GENRE_SOURCES_ROW_PREFIX + "1"))
            assertEquals("Described 2, $bob’s description · L2", textOf(GENRE_SOURCES_ROW_PREFIX + "3"))
        }
    }

    @Test
    fun whyOnAGenreTheDescriptionNamesReadsTheLinesItCameFromAndATagsTheTracksEvidence() =
        show {
            tap(GENRE_PREFIX + "described:$ISLANDMAN:instrumental")
            tap(GENRE_WHY_TAG)
            assertEquals(lines, subscribed["instrumental"])
            assertEquals(lines, explained["instrumental"])
            tap(GENRE_PREFIX + "artist:$ISLANDMAN:house")
            tap(GENRE_WHY_TAG)
            assertEquals(HomeFixtures.evidence, subscribed["house"])
            assertEquals(HomeFixtures.evidence, explained["house"])
        }

    @Test
    fun lyricsFoundDropTheDescriptionsInstrumental() =
        show(tags.copy(singing = Singing(lyrics = true))) {
            assertEquals(
                listOf("artist:$ISLANDMAN:ambient", "artist:$ISLANDMAN:electronic", "artist:$ISLANDMAN:house"),
                chips(),
            )
            assertEquals(0, compose.onAllNodesWithTag(GENRES_ROW_PREFIX + "1").fetchSemanticsNodes().size)
        }

    private companion object {
        const val ISLANDMAN = "islandman"
    }
}
