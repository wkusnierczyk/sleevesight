package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration

/** The genre labels the pipeline assembles: the label sources switched on, combined (#84). */
class PipelineLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val provider = FakeProvider()

    /**
     * A MusicBrainz adapter that gives labels only as the settings configure it, as the real one
     * does, with its own extraction version for each configuration.
     */
    private inner class LabellingMusicBrainz(
        private val labels: Boolean = true,
    ) : SourceAdapter {
        override val id = SourceId.MUSICBRAINZ
        override val extractionVersion get() = if (labels) 2 else 1
        val asked = mutableListOf<Boolean>()

        override fun configuredFor(settings: Settings): SourceAdapter =
            if (labels && !settings.givesLabels(LabelSource.MUSICBRAINZ)) LabellingMusicBrainz(labels = false) else this

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            resolution.await()
            asked += labels
            return SourceResult(
                Outcome.SongArticle,
                listOf(line(id, "A recording by Gordon Lightfoot.")),
                Duration.ZERO,
                genres =
                    if (labels) {
                        vocabulary.labelsFor(
                            listOf("country", "folk", "seen live"),
                            MB_PAGE,
                        )
                    } else {
                        emptyList()
                    },
            )
        }
    }

    private fun lastFm() =
        FakeAdapter(SourceId.LASTFM) { _, _ ->
            SourceResult(
                Outcome.ArtistArticle,
                listOf(line(SourceId.LASTFM, "Gordon Lightfoot has 1 listener.")),
                Duration.ZERO,
                genres = vocabulary.labelsFor(listOf("folk", "singer-songwriter"), LASTFM_PAGE),
            )
        }

    private suspend fun TestScope.track(
        adapters: List<SourceAdapter>,
        cache: PipelineCache = PipelineCache.None,
    ): TrackState =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = adapters,
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }.run(sundown, write = true)

    @Test
    fun `both sources' labels, Last fm's first, credited to each source that gave them`() =
        runTest {
            val state = track(listOf(LabellingMusicBrainz(), lastFm()))
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.map { it.genre.label })
            assertEquals(
                listOf(
                    listOf(LabelSource.LASTFM, LabelSource.MUSICBRAINZ),
                    listOf(LabelSource.LASTFM),
                    listOf(LabelSource.MUSICBRAINZ),
                ),
                state.genres.map { label -> label.credits.map { it.source } },
            )
            assertEquals(listOf(true, true, false), state.genres.map { it.opensCard })
        }

    @Test
    fun `the labels are never evidence, so never in the prompt`() =
        runTest {
            val state = track(listOf(LabellingMusicBrainz(), lastFm()))
            val prompt = provider.requests.single().prompt
            assertFalse("Country" in prompt || "country" in prompt, prompt)
            assertFalse(state.evidence!!.lines.any { "country" in it.text.lowercase() })
        }

    @Test
    fun `a label source switched off gives none, and MusicBrainz then asks for no tags`() =
        runTest {
            settings = settings.copy(labelSources = setOf(LabelSource.LASTFM))
            val musicBrainz = LabellingMusicBrainz()
            val state = track(listOf(musicBrainz, lastFm()))
            assertEquals(listOf("Folk", "Singer-Songwriter"), state.genres.map { it.genre.label })
            assertEquals(
                listOf(listOf(LabelSource.LASTFM)),
                state.genres
                    .map { l ->
                        l.credits.map { it.source }
                    }.distinct(),
            )
            // The adapter as the settings have it: without labels.
            assertEquals(emptyList(), musicBrainz.asked)

            settings = settings.copy(labelSources = setOf(LabelSource.MUSICBRAINZ))
            assertEquals(
                listOf("Country", "Folk"),
                track(listOf(LabellingMusicBrainz(), lastFm())).genres.map { it.genre.label },
            )
        }

    @Test
    fun `a source off under Where to look gives no labels, whatever its label switch`() =
        runTest {
            settings = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.LASTFM)
            val state = track(listOf(LabellingMusicBrainz(), lastFm()))
            assertEquals(listOf("Country", "Folk"), state.genres.map { it.genre.label })
        }

    @Test
    fun `a result cached without labels is not served once MusicBrainz labels are switched on`() =
        runTest {
            val cache = VersionedCache()
            settings = settings.copy(labelSources = setOf(LabelSource.LASTFM))
            track(listOf(LabellingMusicBrainz(), lastFm()), cache)

            settings = settings.copy(labelSources = LabelSource.entries.toSet())
            val musicBrainz = LabellingMusicBrainz()
            val state = track(listOf(musicBrainz, lastFm()), cache)
            assertEquals(listOf(true), musicBrainz.asked)
            assertEquals(listOf("Folk", "Singer-Songwriter", "Country"), state.genres.map { it.genre.label })
        }

    /** A cache keyed by source and extraction version, as the store's is. */
    private class VersionedCache : PipelineCache by PipelineCache.None {
        private val results = mutableMapOf<Pair<SourceId, Int>, SourceResult>()

        override suspend fun result(
            adapter: SourceAdapter,
            track: NormalizedTrack,
            duration: Duration?,
        ): SourceResult? = results[adapter.id to adapter.extractionVersion]

        override suspend fun keep(
            adapter: SourceAdapter,
            track: NormalizedTrack,
            duration: Duration?,
            result: SourceResult,
        ) {
            results.keys.removeAll { it.first == adapter.id }
            results[adapter.id to adapter.extractionVersion] = result
        }
    }

    private companion object {
        const val LASTFM_PAGE = "https://www.last.fm/music/Gordon+Lightfoot"
        const val MB_PAGE = "https://musicbrainz.org/artist/mb-gl"
    }
}
