package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
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
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * What a genre's panel shows above its table, in the app (#523): a genre's definition found once for
 * every track, behind Wikipedia's switch; *Why is this …?* written on a tap, one call, and kept with
 * the track: shown again with no call when the panel is opened again, another genre opened in
 * between, or the track plays again later; forgotten on *Clear history*.
 */
class AppGenreNotesTest {
    private val provider = CountingProvider().apply { reply = { "Its loud guitars are rock [MB1]." } }
    private val cache = CardCache()
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    private val rock = "https://en.wikipedia.org/wiki/Rock_music"
    private val articles =
        object : GenreArticles {
            val asked = mutableListOf<String>()

            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> {
                asked += musicBrainzGenre
                return Lookup.Found(GenreDefinition("Rock music is a genre of popular music.", "Rock music", rock))
            }
        }
    private val definitions = GenreDefinitions(articles, GenreVocabulary.load(JvmResourceReader))

    private fun Lookup<GenreDefinition>?.found(): GenreDefinition? = (this as? Lookup.Found)?.value

    private fun label(tag: String) =
        TrackLabel(
            GenreLabel(tag, "rock", tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
            listOf(LabelCredit(LabelSource.LASTFM, null)),
        )

    private fun track(playing: TrackState = HomeFixtures.track()): TrackState {
        val row = GenreRow(GenreLevel.ARTIST, "A", listOf(label("rock"), label("punk")))
        return playing.copy(genres = TrackGenres(listOf(row)))
    }

    // A clock the tests move.
    private val clock =
        object : Clock {
            var at = Instant.parse("2026-10-27T12:00:00Z")

            override fun now(): Instant = at
        }

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppGenreNotes(definitions, parts, settings, this, epoch, clock = clock)

    @Test
    fun `a genre's definition is found once for every track that has it`() =
        runTest {
            val notes = notes()
            // The panels of three tracks with rock, one after the other.
            repeat(3) {
                assertEquals(
                    rock,
                    notes
                        .definition("rock")
                        .first { it != null }
                        .found()
                        ?.url,
                )
            }
            assertEquals(1, articles.asked.size)
        }

    @Test
    fun `a genre with no article is not found, so its panel can say so`() =
        runTest {
            // *British invasion* is not one of MusicBrainz's genres (#663).
            assertEquals(Lookup.NotFound, notes().definition("britishinvasion").first { it != null })
            assertEquals(0, articles.asked.size)
        }

    @Test
    fun `with Wikipedia off, no definition is looked up`() =
        runTest {
            val now = checkNotNull(settings.value)
            settings.value = now.copy(enabledSources = now.enabledSources - SourceSwitch.WIKIPEDIA)
            assertNull(notes().definition("rock").first())
            assertEquals(0, articles.asked.size)
        }

    @Test
    fun `a definition is looked up once Wikipedia comes on, and gone once it goes off`() =
        runTest {
            val on = checkNotNull(settings.value)
            settings.value = null
            val shown = mutableListOf<String?>()
            val watching = launch { notes().definition("rock").collect { shown += it.found()?.url } }
            advanceUntilIdle()
            // The settings' first read.
            settings.value = on
            advanceUntilIdle()
            settings.value = on.copy(enabledSources = on.enabledSources - SourceSwitch.WIKIPEDIA)
            advanceUntilIdle()
            watching.cancel()
            assertEquals(listOf(null, rock, null), shown)
        }

    @Test
    fun `a reason is written on a tap, once, and shows again on the next opening and after another genre`() =
        runTest {
            val notes = notes()
            val track = track()
            assertEquals(GenreReason.Offered, notes.reason(track, "rock").first())
            notes.explain(track, "rock")
            // A second tap while it is written asks nothing more.
            notes.explain(track, "rock")
            advanceUntilIdle()
            val written = notes.reason(track, "rock").first() as GenreReason.Shown
            assertTrue(written.state is InsightState.Written)
            assertEquals(1, provider.requests)
            // Another genre in between: offered, not written.
            assertEquals(GenreReason.Offered, notes.reason(track, "punk").first())
            // The panel opened again on the first: as written, no call.
            assertEquals(written, notes.reason(track, "rock").first())
            notes.explain(track, "rock")
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a reason says whether its evidence says anything about how the track sounds`() =
        runTest {
            val notes = notes()
            // MusicBrainz's templated line alone: nothing of the sound (#664).
            val templated = EvidenceSet.of(listOf(HomeFixtures.musicBrainzLine), JvmHasher)
            val silent = track(HomeFixtures.track(evidence = templated))
            notes.explain(silent, "rock")
            advanceUntilIdle()
            assertTrue((notes.reason(silent, "rock").first() as GenreReason.Shown).silent)
            // The fixture adds a sentence from the song's Wikipedia article: free text, which may
            // say how it sounds, so the app says nothing.
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertFalse((notes.reason(track(), "rock").first() as GenreReason.Shown).silent)
        }

    @Test
    fun `a reason written before shows again when the track plays again later, with no call`() =
        runTest {
            val first = notes()
            first.explain(track(), "rock")
            advanceUntilIdle()
            // A process later: nothing in memory, the cache keeps it.
            val later = notes()
            val reason = later.reason(track(), "rock")
            reason.first()
            advanceUntilIdle()
            val shown = reason.first() as GenreReason.Shown
            assertTrue((shown.state as InsightState.Written).cached)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a reason shown is offered again once it is older than Keep history for`() =
        runTest {
            settings.value = settings.value?.copy(historyLifetime = 7.days)
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertTrue(notes.reason(track(), "rock").first() is GenreReason.Shown)
            clock.at += 7.days
            assertEquals(GenreReason.Offered, notes.reason(track(), "rock").first())
            // A tap writes it again: the store serves no copy as old (this fake cache cannot tell).
            cache.clearCards()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a shorter Keep history for drops a reason shown at once`() =
        runTest {
            val notes = notes()
            notes.explain(track(), "rock")
            advanceUntilIdle()
            clock.at += 3.days
            val shown = mutableListOf<GenreReason>()
            val watching = launch { notes.reason(track(), "rock").collect { shown += it } }
            advanceUntilIdle()
            settings.value = settings.value?.copy(historyLifetime = 1.days)
            advanceUntilIdle()
            watching.cancel()
            assertTrue(shown.first() is GenreReason.Shown)
            assertEquals(GenreReason.Offered, shown.last())
        }

    @Test
    fun `no reason is offered with Show insights off, or without an AI service`() =
        runTest {
            val notes = notes()
            settings.value = settings.value?.copy(writeInsights = false)
            assertEquals(GenreReason.None, notes.reason(track(), "rock").first())
            settings.value = settings.value?.copy(writeInsights = true)
            parts.value = null
            assertEquals(GenreReason.None, notes.reason(track(), "rock").first())
            notes.explain(track(), "rock")
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `Clear history forgets the reasons shown`() =
        runTest {
            val epoch = ClearEpoch()
            val notes = notes(epoch)
            notes.explain(track(), "rock")
            advanceUntilIdle()
            cache.clearCards()
            epoch.advance()
            notes.forget()
            assertEquals(GenreReason.Offered, notes.reason(track(), "rock").first())
        }
}
