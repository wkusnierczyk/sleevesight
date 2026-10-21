package app.sleevesight.cli

import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
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
    ): TrackResult {
        val nowPlaying = NowPlaying("Song $n", "The Band", "The Album", player = Settings.SPOTIFY)
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
        val first = track(1, listOf(song("One"), formed))
        val second = track(2, listOf(song("Two"), formed, label))

        val rows = ArtistFacts.measure(listOf(first, second), allowModel = false)

        assertEquals(listOf(0, 1), rows.map { it.resent })
        assertEquals(listOf(1, 2), rows.map { it.artistLines })
        val state = second.state
        val prompt = InsightPrompt.build(state.nowPlaying, state.normalized, checkNotNull(state.evidence), false)
        assertEquals(prompt.length, rows[1].promptChars)
        val artistLines = checkNotNull(state.evidence).lines.filter { it.scope == Scope.ARTIST }
        assertEquals(artistLines.sumOf { InsightPrompt.evidenceLine(it).length + 1 }, rows[1].artistChars)
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

        val totals = ArtistFacts.totals(ArtistFacts.measure(listOf(first, second, cached), allowModel = false))

        assertEquals(2, totals.calls)
        assertEquals(1900L, totals.inputTokens)
        assertEquals(180L, totals.outputTokens)
        assertEquals(3, totals.written)
    }
}
