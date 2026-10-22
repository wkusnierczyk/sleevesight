package app.sleevesight.cli

import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.InsightReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Instant

class ArtistFactsTest {
    private val at = Instant.parse("2026-10-21T00:00:00Z")

    private val formed = EvidenceDraft(SourceId.WIKIPEDIA, Scope.ARTIST, "The band formed in 1960.", null, at)
    private val label = EvidenceDraft(SourceId.WIKIDATA, Scope.ARTIST, "The band's label is Parlophone.", null, at)

    private fun song(title: String) = EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "$title is a song.", null, at)

    private fun release(title: String) = EvidenceDraft(SourceId.PLAYER, Scope.RELEASE, "On \"$title\".", null, at)

    private fun track(
        n: Int,
        drafts: List<EvidenceDraft>,
        reply: String? = null,
        usage: Usage? = null,
        title: String = "Song $n",
    ): TrackResult {
        val nowPlaying = NowPlaying(title, "The Band", "The Album", player = Settings.SPOTIFY)
        val evidence = EvidenceSet.of(drafts, JvmHasher)
        val insight =
            reply?.let {
                val written = GroundingCheck.check(it, evidence, allowModel = false) as InsightReply.Written
                InsightState.Written(written.grounded, cached = usage == null, usage = usage)
            } ?: InsightState.Idle(canWrite = false)
        return TrackResult(
            n,
            TrackState(
                nowPlaying = nowPlaying,
                normalized = Normalizer(JvmAccentFolder).normalize(nowPlaying),
                sources = emptyMap(),
                evidence = evidence,
                insight = insight,
            ),
        )
    }

    // A run's events: its `Received`, then each call, its left-out ids and its tokens.
    private fun slice(
        result: TrackResult,
        vararg calls: Pair<List<String>, Usage>,
    ): List<PipelineEvent> {
        val key = result.state.normalized.lookupKey
        return listOf(PipelineEvent.Received(result.state.nowPlaying)) +
            calls.flatMap { (leftOut, usage) ->
                listOf(
                    PipelineEvent.ModelCall(key, ProviderId.ANTHROPIC, "m", leftOut),
                    PipelineEvent.ModelDone(key, usage, StopReason.END, Duration.ZERO),
                )
            }
    }

    private val tokens = Usage(1000, 100)

    @Test
    fun `an artist fact cited again is restated, matched by its text though its id moved`() {
        // Track 1's artist line is W2 (after its song line); track 2 has no song line, so it is W1.
        val first = track(1, listOf(song("One"), formed, label), "One is a song [W1]. The band formed in 1960 [W2].")
        val second = track(2, listOf(formed, label), "The band formed in 1960 [W1]. Its label is Parlophone [WD1].")
        val third = track(3, listOf(song("Three"), formed, label), "Three is a song [W1]. It is on Parlophone [WD1].")

        val rows = ArtistFacts.measure(listOf(first, second, third), allowModel = false)

        assertEquals(listOf("W2"), rows[0].cited)
        assertEquals(emptyList(), rows[0].restated)
        assertEquals(listOf("W1", "WD1"), rows[1].cited)
        assertEquals(listOf("W1"), rows[1].fromLast)
        assertEquals(listOf("W1"), rows[1].restated)
        // Track 3 cites the label alone, which the insight just before it cited.
        assertEquals(listOf("WD1"), rows[2].fromLast)
        assertEquals(listOf("WD1"), rows[2].restated)
        assertEquals(listOf(1, 2, 1), rows.map { it.artistSentences })
        val totals = ArtistFacts.totals(rows)
        assertEquals(2, totals.restatingLast)
        assertEquals(6, totals.artistLines)
    }

    @Test
    fun `a fact cited two insights back is restated from any, not from the last`() {
        val first = track(1, listOf(song("One"), formed), "The band formed in 1960 [W2].")
        val second = track(2, listOf(song("Two"), formed), "Two is a song [W1].")
        val third = track(3, listOf(song("Three"), formed), "The band formed in 1960 [W2].")

        val rows = ArtistFacts.measure(listOf(first, second, third), allowModel = false)

        assertEquals(emptyList(), rows[2].fromLast)
        assertEquals(listOf("W2"), rows[2].restated)
    }

    @Test
    fun `the last insight is the last one written, past a track with none`() {
        val first = track(1, listOf(song("One"), formed), "The band formed in 1960 [W2].")
        val unwritten = track(2, listOf(song("Two"), formed))
        val third = track(3, listOf(song("Three"), formed), "The band formed in 1960 [W2].")

        val rows = ArtistFacts.measure(listOf(first, unwritten, third), allowModel = false)

        assertEquals(listOf("W2"), rows[2].fromLast)
    }

    @Test
    fun `a song line cited again is not an artist fact restated`() {
        val first = track(1, listOf(song("Same"), formed), "Same is a song [W1].")
        val second = track(2, listOf(song("Same"), formed), "Same is a song [W1].")

        val rows = ArtistFacts.measure(listOf(first, second), allowModel = false)

        assertEquals(emptyList(), rows[1].cited)
        assertEquals(0, ArtistFacts.totals(rows).restating)
    }

    @Test
    fun `resent lines are the artist lines the track before had, and the prompt is the one sent`() {
        val first = track(1, listOf(song("One"), formed), "One is a song [W1].", Usage(1000, 100))
        val second = track(2, listOf(song("Two"), formed, label), "Two is a song [W1].", Usage(1000, 100))

        val slices = listOf(slice(first, emptyList<String>() to tokens), slice(second, emptyList<String>() to tokens))
        val rows = ArtistFacts.measure(listOf(first, second), allowModel = false, slices.map(ArtistFacts.RunCalls::of))

        assertEquals(listOf(0, 1), rows.map { it.resent })
        assertEquals(listOf(1, 2), rows.map { it.artistLines })
        val state = second.state
        val prompt = InsightPrompt.build(state.nowPlaying, state.normalized, checkNotNull(state.evidence), false)
        assertEquals(prompt.length, rows[1].promptChars)
        val artistLines = checkNotNull(state.evidence).lines.filter { it.scope == Scope.ARTIST }
        assertEquals(artistLines.sumOf { InsightPrompt.evidenceLine(it).length + 1 }, rows[1].artistChars)
    }

    @Test
    fun `the lines a call left out are not in the prompt measured, nor resent`() {
        val first = track(1, listOf(song("One"), formed, label), "The band formed in 1960 [W2].")
        val second = track(2, listOf(song("Two"), formed, label), "Its label is Parlophone [WD1].")
        val rows =
            ArtistFacts.measure(
                listOf(first, second),
                allowModel = false,
                listOf(slice(first, emptyList<String>() to tokens), slice(second, listOf("W2") to tokens))
                    .map(ArtistFacts.RunCalls::of),
            )

        assertEquals(listOf(0, 1), rows.map { it.leftOut })
        val state = second.state
        val sent = checkNotNull(state.evidence).without(JvmHasher) { it.id == "W2" }
        assertEquals(InsightPrompt.build(state.nowPlaying, state.normalized, sent, false).length, rows[1].promptChars)
        val labelLine = sent.lines.single { it.scope == Scope.ARTIST }
        assertEquals(InsightPrompt.evidenceLine(labelLine).length + 1, rows[1].artistChars)
        assertEquals(1, rows[1].resent)
        assertEquals(emptyList(), rows[1].restated)
        assertEquals(1, ArtistFacts.totals(rows).leftOut)
    }

    @Test
    fun `each run's calls are its own, so a fresh NO_EVIDENCE sent a prompt and a repeat of its track sends its own`() {
        // The same track four times: a call that found no evidence, a call that wrote, from the cache, not written.
        val lines = listOf(song("One"), formed)
        val none =
            track(
                1,
                lines,
                title = "One",
            ).let { it.copy(state = it.state.copy(insight = InsightState.NoEvidence)) }
        val again = track(2, lines, "One is a song [W1].", tokens, title = "One")
        val cached = track(3, lines, "One is a song [W1].", title = "One")
        val unwritten = track(4, lines, title = "One")
        val results = listOf(none, again, cached, unwritten)
        val events =
            slice(none, listOf("W2") to tokens) + slice(again, emptyList<String>() to tokens) +
                slice(cached) + slice(unwritten)

        val rows = ArtistFacts.measure(results, allowModel = false, ArtistFacts.RunCalls.perRun(events))

        assertEquals(listOf(true, true, false, false), rows.map { it.promptChars != null })
        assertEquals(listOf(1, 0, 0, 0), rows.map { it.leftOut })
        assertEquals(2, ArtistFacts.totals(rows).prompts)
    }

    @Test
    fun `a run asked again with every line counts both calls' tokens, and measures the last prompt`() {
        val result = track(1, listOf(song("One"), formed), "One is a song [W1].", tokens)
        val events = slice(result, listOf("W2") to Usage(900, 10), emptyList<String>() to tokens)

        val runs = ArtistFacts.RunCalls.perRun(events)
        val rows = ArtistFacts.measure(listOf(result), allowModel = false, runs)
        val row = rows.single()

        assertEquals(2, row.calls)
        assertEquals(Usage(1900, 110), row.usage)
        assertEquals(0, row.leftOut)
        val state = result.state
        val whole = checkNotNull(state.evidence)
        val prompt = InsightPrompt.build(state.nowPlaying, state.normalized, whole, false)
        assertEquals(prompt.length, row.promptChars)
        // The totals count both prompts sent, the fewer lines' first.
        val fewer = whole.without(JvmHasher) { it.id == "W2" }
        val first = InsightPrompt.build(state.nowPlaying, state.normalized, fewer, false)
        val totals = ArtistFacts.totals(rows)
        assertEquals(2, totals.prompts)
        assertEquals(prompt.length + first.length, totals.promptChars)
        assertEquals("Tokens billed: 1900 in, 110 out, over 2 calls made", Report.billed(runs))
    }

    @Test
    fun `a track with nothing to narrate sends no prompt`() {
        val rows = ArtistFacts.measure(listOf(track(1, listOf(release("The Album")))), allowModel = false)

        assertNull(rows.single().promptChars)
        assertEquals(0, ArtistFacts.totals(rows).prompts)
    }

    @Test
    fun `the totals add the calls' tokens, and a cached insight is no call`() {
        val first = track(1, listOf(song("One"), formed), "One is a song [W1].", Usage(1000, 100))
        val second = track(2, listOf(song("Two"), formed), "Two is a song [W1].", Usage(900, 80))
        val cached = track(3, listOf(song("Three"), formed), "Three is a song [W1].")

        val slices =
            listOf(
                slice(first, emptyList<String>() to Usage(1000, 100)),
                slice(second, emptyList<String>() to Usage(900, 80)),
                slice(cached),
            )
        val runs = ArtistFacts.RunCalls.perRun(slices.flatten())
        val totals = ArtistFacts.totals(ArtistFacts.measure(listOf(first, second, cached), allowModel = false, runs))

        assertEquals("Tokens billed: 1900 in, 180 out, over 2 calls made", Report.billed(runs))
        assertEquals(2, totals.prompts)
        assertEquals(3, totals.written)
    }
}
