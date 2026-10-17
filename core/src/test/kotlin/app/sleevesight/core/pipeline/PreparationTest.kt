package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Preparing the next track, *Evidence* (D13, #20): from a queue item, by name, with no length;
 * only what carries over to the playing track's run; nothing shown, kept as the track's own, or
 * written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparationTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private val resolved = mutableListOf<Pair<String, Duration?>>()
    private val resolver =
        TrackResolver { track, duration ->
            resolved += track.lookupKey to duration
            Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec")
        }

    // MusicBrainz's lines, naming the resolved artist; it prepares ahead, as the real one does.
    private val musicBrainz =
        Ahead(
            FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                SourceResult(Outcome.SongArticle, emptyList(), Duration.ZERO, labelArtists = listOf("artist-1"))
            },
        )
    private val lastFm = Ahead(FakeAdapter(SourceId.LASTFM))
    private val wikipedia = FakeAdapter(SourceId.WIKIPEDIA)
    private val tagRequests = mutableListOf<String>()
    private val labels =
        MusicBrainzLabels({ mbid -> Lookup.Found(listOf("folk")).also { tagRequests += mbid } }, vocabulary)
    private val provider = FakeProvider()
    private val cache = MemoryCache()

    private val steps = mutableListOf<String>()
    private val control =
        object : RunControl {
            override suspend fun await(step: RunStep) {
                steps += "await $step"
            }

            override suspend fun decide(settings: Settings): Boolean = error("a preparation never asks to write")

            override val waitsForTap: Boolean = false

            override suspend fun awaitTap() = error("a preparation never waits for a tap")

            override fun ended(step: RunStep) {
                steps += "ended $step"
            }

            override fun decidedWithoutCall() = error("a preparation decides no insight")
        }

    private fun pipeline() =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(musicBrainz, wikipedia, lastFm),
                resolver = resolver,
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                musicBrainzLabels = labels,
            ),
        ) { settings }

    @Test
    fun `the next track is resolved by name, with what carries over, and nothing else`() =
        runTest {
            val pipeline = pipeline()
            val events = mutableListOf<PipelineEvent>()
            backgroundScope.launch { pipeline.events.toList(events) }
            runCurrent()
            pipeline.prepare(nowPlaying("Next Song", "Next Artist"), control)
            runCurrent()

            assertEquals(listOf<Pair<String, Duration?>>("next artist|next song" to null), resolved)
            assertEquals(listOf("next artist|next song"), musicBrainz.inner.asked)
            assertEquals(listOf("next artist|next song"), lastFm.inner.asked)
            assertEquals(emptyList<String>(), wikipedia.asked, "Wikipedia's results would not carry over")
            assertEquals(listOf("artist-1"), tagRequests)
            assertEquals(
                listOf("await LOOKUPS", "ended LOOKUPS", "await LABELS", "ended LABELS"),
                steps,
            )
            assertNull(pipeline.state.value, "nothing is shown")
            assertTrue(cache.results.isEmpty() && cache.cards.isEmpty(), "nothing is kept as the track's own")
            assertEquals(0, provider.requests.size, "no paid call")
            assertTrue(events.any { it is PipelineEvent.Prepared && it.key == "next artist|next song" })
        }

    @Test
    fun `nothing is prepared with MusicBrainz switched off`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ)
            pipeline().prepare(nowPlaying("Next Song", "Next Artist"), control)
            assertEquals(emptyList<Pair<String, Duration?>>(), resolved)
            assertEquals(emptyList<String>(), steps)
        }

    @Test
    fun `an item with no artists is not prepared`() =
        runTest {
            pipeline().prepare(nowPlaying("Next Song", ""), control)
            assertEquals(emptyList<String>(), steps)
        }

    // [inner], which prepares ahead.
    private class Ahead(
        val inner: FakeAdapter,
    ) : SourceAdapter by inner {
        override val preparesAhead: Boolean = true

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult = inner.lookup(track, resolution)
    }
}
