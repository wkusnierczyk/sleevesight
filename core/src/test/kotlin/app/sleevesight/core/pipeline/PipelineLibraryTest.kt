package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * LRCLIB in a run (#997): asked, once the labels are in, only when a genre is Instrumental and the
 * player gives the track's length, by its first artist, title, album and length in whole seconds; its
 * answer kept with the track's findings, so a replay asks nothing; lyrics drop Instrumental, a 404 or
 * a failure leave it as it was, a failure retryable.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testTimeSource
class PipelineLibraryTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private var settings = Settings()

    // *Nasnusa*'s shape: Sababa 5 tagged instrumental by Last.fm, with Yurika Hanashima credited.
    private val nasnusa =
        NowPlaying(
            title = "Nasnusa - 2024 Remaster",
            artists = "Sababa 5, Yurika Hanashima",
            album = "Kokoro",
            duration = 233_500.milliseconds,
            player = "com.spotify.music",
        )

    private fun lastFm(vararg tags: String) =
        FakeAdapter(SourceId.LASTFM) { _, _ ->
            SourceResult(
                Outcome.ArtistArticle,
                listOf(line(SourceId.LASTFM, "Sababa 5 has 1 listener.")),
                Duration.ZERO,
                genres = vocabulary.labelsFor(tags.toList(), artist = "Sababa 5"),
            )
        }

    /** LRCLIB as written for the test: [answer] for every query, each query recorded. */
    private class FakeLibrary(
        var answer: Lookup<LibraryLyrics>,
    ) : LyricsLibrary {
        val asked = mutableListOf<LibraryQuery>()

        override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> = answer.also { asked += query }
    }

    private fun pipeline(
        library: LyricsLibrary,
        cache: PipelineCache = MemoryCache(),
        adapter: FakeAdapter = lastFm("instrumental", "funk"),
        time: PipelineTime,
    ) = Pipeline(
        PipelineParts(
            normalizer = Normalizer(JvmAccentFolder),
            adapters = listOf(adapter),
            resolver = FakeResolver(),
            hasher = JvmHasher,
            cache = cache,
            lyricsLibrary = library,
        ),
        time,
    ) { settings }

    private fun TrackState.instrumental(): Boolean = genres.all.any { it.genre.identity == TrackGenres.INSTRUMENTAL }

    @Test
    fun `lyrics LRCLIB found drop Instrumental from every row, asked by the first artist, title, album and length`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            val state = pipeline(library, time = PipelineTime(timeSource = testTimeSource)).run(nasnusa, write = false)
            assertFalse(state.instrumental(), "${state.genres.all}")
            assertEquals(listOf("funk"), state.genres.all.map { it.genre.identity })
            // The title as the sources name it; 233.5 s rounded to 234.
            assertEquals(listOf(LibraryQuery("Sababa 5", "Nasnusa", "Kokoro", 234)), library.asked)
            assertEquals(LibraryLyrics.LYRICS, state.genres.singing.library)
        }

    @Test
    fun `LRCLIB's instrumental mark lifts the featured artist's weak sign`() =
        runTest {
            settings = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LRCLIB)
            val off = pipeline(FakeLibrary(Lookup.Found(LibraryLyrics.INSTRUMENTAL)), time = PipelineTime())
            val before = off.run(nasnusa, write = false)
            assertEquals(
                WeakEvidence.Featured("Yurika Hanashima"),
                before.genres.weakEvidence(TrackGenres.INSTRUMENTAL),
            )
            settings = Settings()
            val state =
                pipeline(FakeLibrary(Lookup.Found(LibraryLyrics.INSTRUMENTAL)), time = PipelineTime())
                    .run(nasnusa, write = false)
            assertTrue(state.instrumental())
            assertNull(state.genres.weakEvidence(TrackGenres.INSTRUMENTAL))
        }

    @Test
    fun `the answer is kept with the track's findings, so a replay asks nothing, a 404 included`() =
        runTest {
            val cache = MemoryCache()
            val library = FakeLibrary(Lookup.NotFound)
            val first = pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            // No match says nothing: Instrumental as it was, weak.
            assertTrue(first.instrumental())
            assertEquals(WeakEvidence.Featured("Yurika Hanashima"), first.genres.weakEvidence(TrackGenres.INSTRUMENTAL))
            assertEquals(1, library.asked.size)
            library.answer = Lookup.Found(LibraryLyrics.LYRICS)
            val again = pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            assertEquals(1, library.asked.size)
            assertEquals(LibraryLyrics.NONE, again.genres.singing.library)
            assertTrue(again.instrumental())
            // A lyrics answer kept drops it on the replay, which asks nothing.
            cache.library.clear()
            pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            val replay = pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            assertEquals(2, library.asked.size)
            assertFalse(replay.instrumental())
        }

    @Test
    fun `an unreachable LRCLIB leaves Instrumental as it was, and a Retry naming it asks it again`() =
        runTest {
            val library = FakeLibrary(Lookup.Failed("network"))
            val cache = MemoryCache()
            val pipeline = pipeline(library, cache, time = PipelineTime())
            val failed = mutableListOf<Set<SourceId>>()
            val reporting =
                object : RunControl by RunControl.Unattended(write = false) {
                    override fun labelsFailed(sources: Set<SourceId>) {
                        failed += sources
                    }
                }
            val first = pipeline.run(nasnusa, reporting)
            assertTrue(first.instrumental())
            assertTrue(first.libraryUnreachable)
            assertEquals(listOf(setOf(SourceId.LRCLIB)), failed)
            assertTrue(cache.library.isEmpty())
            val before = Gathered(first.sources, first.resolution, labelsFailed = failed.single())
            // A Retry of another source asks LRCLIB nothing, and keeps it unreachable.
            val other =
                pipeline.run(
                    nasnusa,
                    RunControl.Unattended(write = false),
                    again = before.asking(setOf(SourceId.LASTFM)),
                )
            assertEquals(1, library.asked.size)
            assertTrue(other.libraryUnreachable)
            library.answer = Lookup.Found(LibraryLyrics.LYRICS)
            val retried =
                pipeline.run(
                    nasnusa,
                    RunControl.Unattended(write = false),
                    again = before.asking(setOf(SourceId.LRCLIB)),
                )
            assertEquals(2, library.asked.size)
            assertFalse(retried.libraryUnreachable)
            assertFalse(retried.instrumental())
        }

    @Test
    fun `not asked without the player's length, with its switch off, or with no Instrumental`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            val noLength = pipeline(library, time = PipelineTime()).run(nasnusa.copy(duration = null), write = false)
            assertTrue(noLength.instrumental())
            settings = Settings(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LRCLIB)
            val off = pipeline(library, time = PipelineTime()).run(nasnusa, write = false)
            assertTrue(off.instrumental())
            settings = Settings()
            val sung =
                pipeline(
                    library,
                    adapter = lastFm("funk", "soul"),
                    time = PipelineTime(),
                ).run(nasnusa, write = false)
            assertEquals(listOf("funk", "soul"), sung.genres.all.map { it.genre.identity })
            assertEquals(emptyList(), library.asked)
        }

    @Test
    fun `the next track in the queue is never asked about`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            pipeline(library, time = PipelineTime()).prepare(nasnusa, RunControl.Unattended(write = false))
            assertEquals(emptyList(), library.asked)
        }

    @Test
    fun `of a vocal and an instrumental mix of one length, only the vocal one is asked about`() =
        runTest {
            // LRCLIB knows the vocal mix, of the same album and length, as having lyrics.
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            val cache = MemoryCache()
            val instrumental =
                pipeline(library, cache, time = PipelineTime())
                    .run(nasnusa.copy(title = "Nasnusa - Instrumental Version"), write = false)
            assertTrue(instrumental.instrumental())
            assertEquals(emptyList(), library.asked)
            val vocal = pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            assertFalse(vocal.instrumental())
            assertEquals(1, library.asked.size)
            // Played again as the instrumental mix: the vocal mix's answer, kept, is not read for it.
            val again =
                pipeline(library, cache, time = PipelineTime())
                    .run(nasnusa.copy(title = "Nasnusa - Instrumental Version"), write = false)
            assertTrue(again.instrumental())
        }

    @Test
    fun `another album is another query, asked again rather than served the first's answer`() =
        runTest {
            val library = FakeLibrary(Lookup.NotFound)
            val cache = MemoryCache()
            pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false)
            library.answer = Lookup.Found(LibraryLyrics.LYRICS)
            val corrected =
                pipeline(
                    library,
                    cache,
                    time = PipelineTime(),
                ).run(nasnusa.copy(album = "Kokoro (Deluxe)"), write = false)
            assertEquals(listOf("Kokoro", "Kokoro (Deluxe)"), library.asked.map { it.album })
            assertFalse(corrected.instrumental())
        }

    @Test
    fun `an off-vocal or no-vocals mix is never asked about, nor served the vocal mix's kept answer`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            val cache = MemoryCache()
            // The vocal mix asked about first: its lyrics kept.
            assertFalse(pipeline(library, cache, time = PipelineTime()).run(nasnusa, write = false).instrumental())
            assertEquals(1, cache.library.size)
            for (title in listOf("Nasnusa - Off Vocal Version", "Nasnusa - No Vocals Version")) {
                val mix =
                    pipeline(
                        library,
                        cache,
                        time = PipelineTime(),
                    ).run(nasnusa.copy(title = title), write = false)
                assertTrue(mix.instrumental(), title)
                assertNull(mix.genres.singing.library, title)
            }
            assertEquals(1, library.asked.size)
        }

    // Last.fm unreachable at first, then answering with [tags] for Sababa 5: as a *Retry* finds it.
    private fun flakyLastFm(vararg tags: String): Pair<FakeAdapter, () -> Unit> {
        var reachable = false
        val adapter =
            FakeAdapter(SourceId.LASTFM) { _, _ ->
                if (reachable) {
                    SourceResult(
                        Outcome.ArtistArticle,
                        listOf(line(SourceId.LASTFM, "Sababa 5 has 1 listener.")),
                        Duration.ZERO,
                        genres = vocabulary.labelsFor(tags.toList(), artist = "Sababa 5"),
                    )
                } else {
                    SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO)
                }
            }
        return adapter to { reachable = true }
    }

    @Test
    fun `a Retry of another source that brings Instrumental asks LRCLIB once`() =
        runTest {
            val library = FakeLibrary(Lookup.Found(LibraryLyrics.LYRICS))
            val (lastFm, reach) = flakyLastFm("instrumental", "funk")
            val pipeline = pipeline(library, adapter = lastFm, time = PipelineTime())
            val first = pipeline.run(nasnusa, RunControl.Unattended(write = false))
            assertTrue(first.genres.isEmpty)
            assertEquals(emptyList(), library.asked)
            reach()
            val before = Gathered(first.sources, first.resolution)
            val retried =
                pipeline.run(
                    nasnusa,
                    RunControl.Unattended(write = false),
                    again = before.asking(setOf(SourceId.LASTFM)),
                )
            assertEquals(1, library.asked.size)
            assertFalse(retried.instrumental())
            assertEquals(listOf("funk"), retried.genres.all.map { it.genre.identity })
        }

    @Test
    fun `a failure kept from before is not shown once the genres no longer call for LRCLIB`() =
        runTest {
            val library = FakeLibrary(Lookup.Failed("network"))
            var tags = listOf("instrumental", "funk")
            val lastFm =
                FakeAdapter(SourceId.LASTFM) { _, _ ->
                    SourceResult(
                        Outcome.ArtistArticle,
                        listOf(line(SourceId.LASTFM, "Sababa 5 has 1 listener.")),
                        Duration.ZERO,
                        genres = vocabulary.labelsFor(tags, artist = "Sababa 5"),
                        provisional = true,
                    )
                }
            val pipeline = pipeline(library, adapter = lastFm, time = PipelineTime())
            val failed = mutableListOf<Set<SourceId>>()
            val reporting =
                object : RunControl by RunControl.Unattended(write = false) {
                    override fun labelsFailed(sources: Set<SourceId>) {
                        failed += sources
                    }
                }
            val first = pipeline.run(nasnusa, reporting)
            assertTrue(first.libraryUnreachable)
            assertEquals(listOf(setOf(SourceId.LRCLIB)), failed)
            // A *Retry* of Last.fm, whose tags now leave Instrumental out: nothing left to ask LRCLIB.
            tags = listOf("funk", "soul")
            val before = Gathered(first.sources, first.resolution, labelsFailed = failed.single())
            failed.clear()
            val retried = pipeline.run(nasnusa, reporting, again = before.asking(setOf(SourceId.LASTFM)))
            assertFalse(retried.libraryUnreachable)
            assertTrue(failed.none { SourceId.LRCLIB in it })
            assertEquals(1, library.asked.size)
        }
}
