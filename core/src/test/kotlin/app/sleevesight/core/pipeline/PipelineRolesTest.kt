package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.ItemPrompt
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The roles MusicBrainz tells once resolved (#1180) reach what the run asks with: the key insight's
 * prompt, and the song's insight's prompt and its review search, for a track whose title names no
 * composer (*Turandot: "Nessun dorma!"*, Puccini credited first).
 */
class PipelineRolesTest {
    private val aria =
        nowPlaying(
            "Turandot: \"Nessun dorma!\"",
            "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta, Los Angeles Philharmonic",
            album = "The Three Tenors in Concert 1994",
        )
    private val told =
        ArtistRoles(listOf("Giacomo Puccini"), listOf("Luciano Pavarotti", "Zubin Mehta", "Los Angeles Philharmonic"))
    private val resolved = Resolution(outcome = Outcome.SongArticle, recordingMbid = "rec-1", roles = told)
    private val roleLines =
        "\nComposer: Giacomo Puccini\nPerformers: Luciano Pavarotti, Zubin Mehta, Los Angeles Philharmonic\n"

    @Test
    fun `the key insight's prompt names the composer and the performers MusicBrainz tells`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is an aria [W1].") }
            val wikipedia =
                FakeAdapter(SourceId.WIKIPEDIA, byName = 200.milliseconds) { _, _ ->
                    SourceResult(
                        Outcome.SongArticle,
                        listOf(line(SourceId.WIKIPEDIA, "Wikipedia line.")),
                        200.milliseconds,
                    )
                }
            val pipeline =
                Pipeline(
                    PipelineParts(
                        normalizer = Normalizer(JvmAccentFolder),
                        adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ), wikipedia),
                        resolver = FakeResolver(resolution = resolved),
                        hasher = JvmHasher,
                        ai = AiAccess(provider, Secret("sk-test")),
                        cache = MemoryCache(),
                    ),
                    PipelineTime(timeSource = testTimeSource),
                ) { Settings(aiProvider = ProviderId.ANTHROPIC) }
            assertIs<InsightState.Written>(pipeline.run(aria, write = true).insight)
            val prompt = provider.requests.single().prompt
            assertTrue(roleLines in prompt, prompt)
            assertFalse("Primary artist:" in prompt)
        }

    @Test
    fun `the song's insight's prompt and its review search name them too`() {
        val normalized = Normalizer(JvmAccentFolder).normalize(aria)
        val track = TrackState(aria, normalized, emptyMap(), resolution = resolved)
        val question =
            SongQuestion(
                ItemPrompt.Subject(CardKind.SONG, aria.title),
                track,
                allowModel = false,
                asked = ItemEvidence(EvidenceSet.of(emptyList(), JvmHasher), emptyMap()),
                own = EvidenceSet.of(emptyList(), JvmHasher),
                hasher = JvmHasher,
                language = null,
                line = { null },
            )
        assertTrue("Luciano Pavarotti" in question.query, question.query)
        assertFalse("Puccini" in question.query, question.query)
        assertTrue(roleLines in question.knowledge, question.knowledge)
    }
}
