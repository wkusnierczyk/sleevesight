package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptChoice
import app.sleevesight.core.synthesis.PromptChoices
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The prompt lab's choices in the pipeline (#1003): taken once with the AI access a pipeline is built
 * with, they key each card and name what wrote it and which request, so a card is kept under the
 * variant that wrote it whatever the lab is switched to while it is written.
 */
class LabChoicesTest {
    private val reply = "Its modal harmony and muted trumpet are jazz [W1]."
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val cache = MemoryCache()

    private val variantA =
        PromptChoices(
            mapOf(
                PromptKind.GENRE_REASONS to
                    PromptChoice("A", LabPrompts.variant(PromptKind.GENRE_REASONS, "A")!!.instructions),
            ),
        )
    private val release = PromptChoices(mapOf(PromptKind.GENRE_REASONS to PromptChoice(LabPrompts.RELEASE, null)))

    private fun track(): TrackState {
        val playing = nowPlaying("So What", "Miles Davis", "Kind of Blue")
        val drafts = listOf(line(SourceId.WIKIPEDIA, "So What is a modal jazz composition by Miles Davis.", Scope.SONG))
        val label = GenreLabel("jazz", "Jazz", "jazz", null, GenreLevel.ARTIST, "Miles Davis")
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false)),
            evidence = EvidenceSet.of(drafts, JvmHasher),
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            "Miles Davis",
                            listOf(TrackLabel(label, listOf(LabelCredit(LabelSource.LASTFM, null)))),
                        ),
                    ),
                ),
        )
    }

    private fun reasons(
        provider: FakeProvider,
        choices: PromptChoices,
    ) = GenreReasons(AiAccess(provider, Secret("k"), choices), cache, JvmHasher)

    @Test
    fun `a card written with a variant is keyed by it and names it, and its request names the card`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(reply) }
            val track = track()
            val key = checkNotNull(reasons(provider, variantA).key(track, "jazz", settings))
            val written =
                assertIs<InsightState.Written>(reasons(provider, variantA).write(key, track, "Jazz") { _, _ -> })

            val kept = cache.cards.keys.single()
            assertEquals("A", kept.variant)
            assertEquals(kept.card, written.writtenBy?.card)
            assertEquals("A", written.writtenBy?.variant)
            assertEquals(kept.card, provider.requests.single().card)
            val plain = checkNotNull(reasons(provider, PromptChoices.NONE).key(track, "jazz", settings))
            assertNotEquals(plain.styleHash, kept.styleHash)
            assertNull(plain.card)
        }

    @Test
    fun `the release prompt keeps the release card's key, named as the release prompt's`() {
        val provider = FakeProvider { FakeProvider.done(reply) }
        val track = track()
        val plain = checkNotNull(reasons(provider, PromptChoices.NONE).key(track, "jazz", settings))
        val labbed = checkNotNull(reasons(provider, release).key(track, "jazz", settings))
        assertEquals(plain.styleHash, labbed.styleHash)
        assertEquals(LabPrompts.RELEASE, labbed.variant)
    }

    @Test
    fun `a card is kept under the variant it was asked with though the lab is switched while it streams`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(reply) }
            val slow = FakeProvider(delay = 1.seconds) { FakeProvider.done(reply) }
            val track = track()
            val withA = reasons(slow, variantA)
            val key = checkNotNull(withA.key(track, "jazz", settings))
            val writing = async { withA.write(key, track, "Jazz") { _, _ -> } }
            testScheduler.advanceTimeBy(500.milliseconds)
            // The lab switched back to the release prompt while the reply streams: the pipeline it
            // rebuilds takes the release prompt's choices; the call under way keeps its own.
            assertEquals(1, slow.requests.size)
            assertEquals(emptyMap(), cache.cards)
            val rebuilt = reasons(provider, release)
            val releaseKey = checkNotNull(rebuilt.key(track, "jazz", settings))
            writing.await()

            assertEquals(listOf("A"), cache.cards.keys.map { it.variant })
            assertNull(rebuilt.kept(releaseKey, track))
            assertIs<InsightState.Written>(withA.kept(key, track)?.state)
        }
}
