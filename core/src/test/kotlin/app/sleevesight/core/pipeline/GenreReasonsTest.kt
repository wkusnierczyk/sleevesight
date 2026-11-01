package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
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
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreReasonPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val lists = GenreLists(AiAccess(provider, Secret("k")), cache, JvmHasher, vocabulary)

    // *AI service knowledge* on (off by default): the AI's genre list can run.
    private val knowing = settings.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)

    @Test
    fun `a genre only MusicBrainz gives asks the AI's genre list first, then is written once listed (#762)`() {
        val only = track(credits = listOf(LabelSource.MUSICBRAINZ))
        val listing = assertIs<ReasonPath.Listing>(reasons.path(only, "jazz", knowing, lists, listed = null))
        assertEquals(CardKind.GENRE_LIST, listing.key.kind)
        // On the list: path A, the reason's own key.
        val reason = assertIs<ReasonPath.Reason>(reasons.path(only, "jazz", knowing, lists, setOf("jazz")))
        assertEquals("jazz", reason.key.subject)
        // Not on it: no call.
        assertEquals(ReasonPath.NotListed, reasons.path(only, "jazz", knowing, lists, setOf("blues")))
        // Given by another source too: path A, whatever the list.
        val both = track(credits = listOf(LabelSource.MUSICBRAINZ, LabelSource.LASTFM))
        assertIs<ReasonPath.Reason>(reasons.path(both, "jazz", knowing, lists, listed = null))
        assertIs<ReasonPath.Reason>(reasons.path(both, "jazz", knowing, lists, setOf("blues")))
    }

    @Test
    fun `the genre list cannot run with the model's knowledge off, and says so (#762)`() {
        val only = track(credits = listOf(LabelSource.MUSICBRAINZ))
        // Off by default.
        val off = settings
        assertFalse(off.isEnabled(SourceId.MODEL))
        assertEquals(ReasonPath.CannotList, reasons.path(only, "jazz", off, lists, listed = null))
        assertNull(lists.key(only, off))
        // A genre another source gives is written all the same.
        assertIs<ReasonPath.Reason>(reasons.path(track, "jazz", off, lists, listed = null))
    }

    @Test
    fun `no AI service says so, and nothing with insights off, no evidence, or a genre the track lacks`() {
        val only = track(credits = listOf(LabelSource.MUSICBRAINZ))
        val none = GenreReasons(null, cache, JvmHasher)
        for (given in listOf(track, only)) {
            assertEquals(ReasonPath.NoService, none.path(given, "jazz", knowing, lists, listed = null))
            assertEquals(
                ReasonPath.None,
                reasons.path(given, "jazz", settings.copy(writeInsights = false), lists, null),
            )
            assertEquals(ReasonPath.None, reasons.path(given.copy(evidence = null), "jazz", settings, lists, null))
        }
        assertEquals(ReasonPath.None, reasons.path(track, "rock", settings, lists, listed = null))
    }

    @Test
    fun `no model, or a source still pending, offers nothing to write (#721)`() {
        // A service with no default model and none set: Ollama's, until one is chosen.
        val ollama =
            object : AiProvider by provider {
                override val id: ProviderId = ProviderId.OLLAMA
            }
        val modelless = GenreReasons(AiAccess(ollama, Secret("k")), cache, JvmHasher)
        val noModel = Settings(aiProvider = ProviderId.OLLAMA)
        for (credits in listOf(listOf(LabelSource.LASTFM), listOf(LabelSource.MUSICBRAINZ))) {
            val given = track(credits = credits)
            assertNull(modelless.key(given, "jazz", noModel))
            assertEquals(ReasonPath.NoService, modelless.path(given, "jazz", noModel, lists, null))
            // Evidence in, but a source still to land.
            val pending = given.copy(sources = given.sources + (SourceId.LASTFM to SourceState.Pending))
            assertNotNull(pending.evidence)
            assertNull(reasons.key(pending, "jazz", settings))
            assertEquals(ReasonPath.None, reasons.path(pending, "jazz", settings, lists, null))
        }
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
    fun `a reply that broke off says how, and one with no text fails, and neither is kept (#665)`() =
        runTest {
            val cases =
                listOf(
                    brokeOff(BreakoffCause.CONNECTION_LOST) to ReplyFailures.CONNECTION_LOST,
                    brokeOff(BreakoffCause.TIMED_OUT) to ReplyFailures.TIMED_OUT,
                    brokeOff(BreakoffCause.NO_END) to ReplyFailures.INCOMPLETE,
                    FakeProvider.done("", StopReason.MAX_TOKENS) to ReplyFailures.LENGTH_LIMIT,
                    FakeProvider.done(" \n", StopReason.END) to ReplyFailures.EMPTY,
                )
            for ((reply, label) in cases) {
                provider.reply = { reply }
                assertEquals(InsightState.Failed(label), write(), label)
            }
            assertEquals(0, cache.cards.size)
        }

    private fun brokeOff(cause: BreakoffCause): List<CompletionEvent> =
        listOf(
            CompletionEvent.Delta("Half"),
            CompletionEvent.Error(AiErrorKind.INCOMPLETE, breakoff = Breakoff(cause, Usage(900, 0))),
        )

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
