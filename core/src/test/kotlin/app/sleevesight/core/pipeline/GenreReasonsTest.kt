package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
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
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreReasonPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Instant

/** *Why is this …?* (#523): one call on a tap, checked, and kept in the synthesis cache like the insight. */
class GenreReasonsTest {
    private val provider = FakeProvider { FakeProvider.done("Its modal harmony and muted trumpet are jazz [W1].") }
    private val cache = MemoryCache()
    private val reasons = GenreReasons(AiAccess(provider, Secret("k")), cache, JvmHasher)
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val track = track()

    private fun track(
        lines: List<Pair<SourceId, String>> =
            listOf(SourceId.WIKIPEDIA to "So What is a modal jazz composition by Miles Davis."),
        credits: List<LabelSource> = listOf(LabelSource.LASTFM),
    ): TrackState {
        val playing = nowPlaying("So What", "Miles Davis", "Kind of Blue")
        val drafts = lines.map { (source, text) -> line(source, text, Scope.SONG) }
        val label = GenreLabel("jazz", "jazzblues", "Jazz", "jazz", null, GenreLevel.ARTIST, "Miles Davis")
        val given = credits.map { LabelCredit(it, null) }
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                ),
            evidence = EvidenceSet.of(drafts, JvmHasher),
            genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Miles Davis", listOf(TrackLabel(label, given))))),
        )
    }

    private suspend fun write(
        on: TrackState = track,
        shown: MutableList<InsightState> = mutableListOf(),
    ): InsightState {
        val key = checkNotNull(reasons.key(on, "jazz", settings))
        return reasons.write(key, on, "Jazz") { state, _ -> shown += state }
    }

    @Test
    fun `a reason is written by one call, about the genre, checked, and kept under the genre's key`() =
        runTest {
            val shown = mutableListOf<InsightState>()
            val written = assertIs<InsightState.Written>(write(shown = shown))
            assertEquals(1, provider.requests.size)
            assertTrue("\"Jazz\"" in provider.requests.single().prompt)
            assertEquals(1, written.grounded.counts.ok)
            assertEquals(false, written.cached)
            assertIs<InsightState.Writing>(shown.first())
            assertEquals(written, shown.last())
            val key = cache.cards.keys.single()
            assertEquals(CardKind.GENRE, key.kind)
            assertEquals("jazz", key.subject)
            assertEquals(GenreReasonPrompt.VERSION, key.promptVersion)
            assertEquals(track.evidence?.fingerprint, key.evidenceFingerprint)
        }

    @Test
    fun `a reason once written is found again with no call`() =
        runTest {
            write()
            val key = checkNotNull(reasons.key(track, "jazz", settings))
            val kept = assertIs<InsightState.Written>(reasons.kept(key, track)?.state)
            assertTrue(kept.cached)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `each genre of a track keeps its own reason, apart from the insight`() {
        val jazz = checkNotNull(reasons.key(track, "jazz", settings))
        val other = jazz.copy(subject = "modaljazz")
        assertNotEquals(jazz, other)
        assertNotEquals(CardKind.INSIGHT, jazz.kind)
    }

    @Test
    fun `a settings change that would offer Rewrite misses the reason kept`() =
        runTest {
            write()
            val effort = Settings(aiProvider = ProviderId.ANTHROPIC, aiModels = mapOf(ProviderId.ANTHROPIC to "other"))
            val key = checkNotNull(reasons.key(track, "jazz", effort))
            assertNull(reasons.kept(key, track))
        }

    @Test
    fun `no reason is offered for a genre only MusicBrainz gives, nor without an AI service or with insights off`() {
        assertNull(reasons.key(track(credits = listOf(LabelSource.MUSICBRAINZ)), "jazz", settings))
        val both = track(credits = listOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM))
        assertNotNull(reasons.key(both, "jazz", settings))
        assertNull(GenreReasons(null, cache, JvmHasher).key(track, "jazz", settings))
        assertNull(reasons.key(track, "jazz", settings.copy(writeInsights = false)))
        assertNull(reasons.key(track.copy(evidence = null), "jazz", settings))
        assertNull(reasons.key(track, "rock", settings.copy(aiProvider = null)))
    }

    @Test
    fun `evidence with nothing to narrate makes no call`() =
        runTest {
            val thin = track(lines = listOf(SourceId.PLAYER to "Released on Kind of Blue."))
            assertEquals(InsightState.NoEvidence, write(thin))
            assertEquals(0, provider.requests.size)
        }

    @Test
    fun `NO_EVIDENCE is shown as none, and kept`() =
        runTest {
            provider.reply = { FakeProvider.done("NO_EVIDENCE") }
            assertEquals(InsightState.NoEvidence, write())
            assertEquals(1, cache.cards.size)
        }

    @Test
    fun `a failed or declined call is shown as failed, and nothing is kept`() =
        runTest {
            provider.reply = { listOf(CompletionEvent.Error(AiErrorKind.entries.first())) }
            assertIs<InsightState.Failed>(write())
            provider.reply = { FakeProvider.done("No.", StopReason.REFUSAL) }
            assertEquals(InsightState.Failed(GenreReasons.DECLINED), write())
            assertEquals(0, cache.cards.size)
        }

    @Test
    fun `a reply cut off is shown, and not kept`() =
        runTest {
            provider.reply = { FakeProvider.done("It is jazz [W1].", StopReason.MAX_TOKENS) }
            val written = assertIs<InsightState.Written>(write())
            assertEquals(false, written.complete)
            assertEquals(0, cache.cards.size)
        }

    @Test
    fun `a cache that fails is a miss, and the reason is still written`() =
        runTest {
            var writes = 0
            val failing =
                object : PipelineCache by PipelineCache.None {
                    override suspend fun card(key: CardKey): String = error("cache down")

                    override suspend fun keep(
                        key: CardKey,
                        text: String,
                    ) = error("cache down")

                    override suspend fun keep(
                        key: CardKey,
                        text: String,
                        writtenAt: Instant,
                    ) {
                        writes++
                        error("cache down")
                    }
                }
            val on = GenreReasons(AiAccess(provider, Secret("k")), failing, JvmHasher)
            val key = checkNotNull(on.key(track, "jazz", settings))
            assertNull(on.kept(key, track))
            assertIs<InsightState.Written>(on.write(key, track, "Jazz") { _, _ -> })
            // The write was tried, and failed.
            assertEquals(1, writes)
        }

    @Test
    fun `a reason kept is kept with the time it is shown with, in whole milliseconds`() =
        runTest {
            val (written, at, kept) = timed(FakeProvider.done("Its modal harmony and muted trumpet are jazz [W1]."))
            assertIs<InsightState.Written>(written)
            assertEquals(at, kept.keptAt.values.single())
            assertEquals(at, Instant.fromEpochMilliseconds(at.toEpochMilliseconds()))
        }

    @Test
    fun `NO_EVIDENCE is kept with the time it is shown with`() =
        runTest {
            val (shown, at, kept) = timed(FakeProvider.done("NO_EVIDENCE"))
            assertEquals(InsightState.NoEvidence, shown)
            assertEquals(at, kept.keptAt.values.single())
        }

    @Test
    fun `a reply cut off is not kept, and is shown with a time in whole milliseconds`() =
        runTest {
            val (shown, at, kept) = timed(FakeProvider.done("It is jazz [W1].", StopReason.MAX_TOKENS))
            assertEquals(false, assertIs<InsightState.Written>(shown).complete)
            assertEquals(at, Instant.fromEpochMilliseconds(at.toEpochMilliseconds()))
            assertTrue(kept.cards.isEmpty())
            assertTrue(kept.keptAt.isEmpty())
        }

    // [reply] written by a reasons writer whose clock moves on, by less than a millisecond, each time
    // it is read, into a cache of its own: the last state shown, its time, and the cache.
    private suspend fun timed(reply: List<CompletionEvent>): Triple<InsightState, Instant, MemoryCache> {
        val clock =
            object : Clock {
                var at = Instant.parse("2026-10-28T12:00:00.000400Z")

                override fun now(): Instant = at.also { at += 300.microseconds }
            }
        provider.reply = { reply }
        val kept = MemoryCache()
        val timed = GenreReasons(AiAccess(provider, Secret("k")), kept, JvmHasher, clock)
        val key = checkNotNull(timed.key(track, "jazz", settings))
        val shown = mutableListOf<Pair<InsightState, Instant>>()
        val last = timed.write(key, track, "Jazz") { state, at -> shown += state to at }
        assertEquals(last, shown.last().first)
        return Triple(last, shown.last().second, kept)
    }
}
