package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/**
 * A genre's reason asked again when the AI service is busy (#1164, `AiRetry`): each try counted, and
 * made only while its gate (`GenreReasons.write`'s `mayRetry`: the app's, the pipeline it was asked
 * on still the live one) says so.
 */
class GenreReasonRetryTest {
    private val busy = CompletionEvent.Error(AiErrorKind.OVERLOADED)
    private var failing = true
    private val provider =
        FakeProvider {
            if (failing) listOf(busy) else FakeProvider.done("Its modal harmony and muted trumpet are jazz [W1].")
        }
    private val noJitter =
        object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }

    private val track: TrackState =
        run {
            val playing = nowPlaying("So What", "Miles Davis", "Kind of Blue")
            val drafts =
                listOf(line(SourceId.WIKIPEDIA, "So What is a modal jazz composition by Miles Davis.", Scope.SONG))
            val label = GenreLabel("jazz", "Jazz", "jazz", null, GenreLevel.ARTIST, "Miles Davis")
            TrackState(
                nowPlaying = playing,
                normalized = Normalizer(JvmAccentFolder).normalize(playing),
                sources =
                    mapOf(
                        SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                    ),
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

    @Test
    fun `a gate that closes during the wait sends no second request`() =
        runTest {
            val access = AiAccess(AiRetry(provider, { 30.seconds }, testScheduler.timeSource, noJitter), Secret("k"))
            val reasons = GenreReasons(access, MemoryCache(), JvmHasher)
            val key = checkNotNull(reasons.key(track, "jazz", Settings(aiProvider = ProviderId.ANTHROPIC)))
            var live = true
            launch {
                delay(1.seconds)
                // Another pipeline built: a key saved, the AI service switched.
                live = false
                failing = false
            }
            val state = reasons.write(key, track, "Jazz", mayRetry = { live }) { _, _ -> }
            assertEquals(InsightState.Failed("overloaded"), state)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `a try again is counted, and answered while the gate stays open`() =
        runTest {
            val access = AiAccess(AiRetry(provider, { 30.seconds }, testScheduler.timeSource, noJitter), Secret("k"))
            val reasons = GenreReasons(access, MemoryCache(), JvmHasher)
            val key = checkNotNull(reasons.key(track, "jazz", Settings(aiProvider = ProviderId.ANTHROPIC)))
            launch {
                delay(1.seconds)
                failing = false
            }
            val counted = mutableListOf<QuestionCost>()
            val state = reasons.write(key, track, "Jazz", counted = { counted += it }) { _, _ -> }
            assertIs<InsightState.Written>(state)
            assertEquals(2, provider.requests.size)
            assertEquals(listOf(QuestionCost.CALL, QuestionCost.CALL), counted)
        }
}
