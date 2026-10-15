package io.github.wkusnierczyk.sleevesight.cli

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceSet
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.pipeline.InsightState
import io.github.wkusnierczyk.sleevesight.core.pipeline.SourceState
import io.github.wkusnierczyk.sleevesight.core.pipeline.TrackState
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.core.synthesis.GroundingCheck
import io.github.wkusnierczyk.sleevesight.core.synthesis.InsightReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

class GateTest {
    private val at = Instant.parse("2026-10-14T00:00:00Z")

    private fun state(
        n: Int,
        wikipedia: SourceResult? = null,
        lastFm: SourceResult? = null,
        reply: String? = null,
    ): TrackResult {
        val nowPlaying = Cli.nowPlaying(n)
        val evidence =
            EvidenceSet.of(
                listOf(EvidenceDraft(SourceId.MUSICBRAINZ, Scope.SONG, "A fact.", null, at)),
                JvmHasher,
            )
        val insight =
            reply?.let {
                val written = GroundingCheck.check(it, evidence, allowModel = false) as InsightReply.Written
                InsightState.Written(written.grounded, cached = false)
            } ?: InsightState.Idle(canWrite = false)
        return TrackResult(
            n,
            TrackState(
                nowPlaying = nowPlaying,
                normalized = Normalizer(JvmAccentFolder).normalize(nowPlaying),
                sources =
                    listOfNotNull(
                        wikipedia?.let { SourceId.WIKIPEDIA to SourceState.Done(it, cached = false) },
                        lastFm?.let { SourceId.LASTFM to SourceState.Done(it, cached = false) },
                    ).toMap(),
                evidence = evidence,
                insight = insight,
            ),
        )
    }

    private fun lines(scope: Scope) =
        SourceResult(
            Outcome.SongArticle,
            listOf(EvidenceDraft(SourceId.WIKIPEDIA, scope, "A line.", null, at)),
            Duration.ZERO,
        )

    private fun verdicts(vararg results: TrackResult) =
        Gate.evaluate(results.toList(), insightsAsked = true).map {
            it.verdict
        }

    private fun collision(vararg results: TrackResult) = Gate.evaluate(results.toList(), insightsAsked = false)[2]

    @Test
    fun `nothing to check is not checked, not passed, and not incomplete`() {
        val criteria = Gate.evaluate(listOf(state(1)), insightsAsked = false)
        assertEquals(listOf(Verdict.NOT_CHECKED, Verdict.NOT_CHECKED, Verdict.NOT_CHECKED), criteria.map { it.verdict })
        assertTrue(criteria.none { it.incomplete })
    }

    @Test
    fun `an unreachable or failed source on a listed track leaves the collision unchecked, not passed`() {
        val unreachable = SourceResult(Outcome.Unreachable, emptyList(), Duration.ZERO)
        val track8 = collision(state(8, wikipedia = unreachable))
        assertEquals(Verdict.NOT_CHECKED, track8.verdict)
        assertTrue(track8.incomplete)
        assertTrue("unreachable" in track8.detail, track8.detail)

        val failed =
            state(12).let { r ->
                r.copy(
                    state =
                        r.state.copy(
                            sources =
                                mapOf(
                                    SourceId.WIKIPEDIA to SourceState.Failed("IllegalStateException"),
                                ),
                        ),
                )
            }
        val track12 = collision(failed, state(20, wikipedia = lines(Scope.SONG)))
        assertEquals(Verdict.NOT_CHECKED, track12.verdict)
        assertTrue(track12.incomplete)

        // A failure elsewhere is still a failure; a source switched off is no check at all.
        assertEquals(
            Verdict.FAIL,
            collision(state(8, wikipedia = unreachable), state(45, wikipedia = lines(Scope.SONG))).verdict,
        )
        val off = SourceResult(Outcome.SourceOff, emptyList(), Duration.ZERO)
        val disabled = collision(state(8, wikipedia = off))
        assertEquals(Verdict.NOT_CHECKED, disabled.verdict)
        assertFalse(disabled.incomplete)
    }

    @Test
    fun `a written insight that fails a criterion fails it, though another insight failed`() {
        val failed = state(1).let { r -> r.copy(state = r.state.copy(insight = InsightState.Failed("offline"))) }
        val criteria = Gate.evaluate(listOf(state(63, reply = "It is a song [W9]."), failed), insightsAsked = true)
        assertEquals(Verdict.FAIL, criteria[0].verdict)
        assertFalse(criteria[0].incomplete)
        assertTrue("1 failed (1: offline)" in criteria[0].detail, criteria[0].detail)
        // The uncited criterion, which the written insight passes, stays unchecked.
        assertEquals(Verdict.NOT_CHECKED, criteria[1].verdict)
        assertTrue(criteria[1].incomplete)
    }

    @Test
    fun `failed insights are counted, and leave the citation criteria unchecked`() {
        val failed = state(1).let { r -> r.copy(state = r.state.copy(insight = InsightState.Failed("offline"))) }
        val criteria = Gate.evaluate(listOf(state(63, reply = "It is a song [MB1]."), failed), insightsAsked = true)
        assertEquals(Verdict.NOT_CHECKED, criteria[0].verdict)
        assertTrue(criteria[0].incomplete)
        assertTrue("1 of 2 insights written; 1 failed (1: offline)" in criteria[0].detail, criteria[0].detail)
    }

    @Test
    fun `a cover's original-song article is right, its own song article is not`() {
        assertEquals(Verdict.PASS, verdicts(state(45, wikipedia = lines(Scope.ORIGINAL_SONG)))[2])
        assertEquals(Verdict.FAIL, verdicts(state(45, wikipedia = lines(Scope.SONG)))[2])
    }

    @Test
    fun `the wrong Reem and Jessiah's Last-fm namesake must not be taken`() {
        assertEquals(Verdict.FAIL, verdicts(state(12, wikipedia = lines(Scope.ARTIST)))[2])
        val accepted = SourceResult(Outcome.ArtistArticle, emptyList(), Duration.ZERO)
        assertEquals(Verdict.FAIL, verdicts(state(61, lastFm = accepted))[2])
        assertEquals(
            Verdict.PASS,
            verdicts(state(61, lastFm = SourceResult(Outcome.Rejected("Jesaiah"), emptyList(), Duration.ZERO)))[2],
        )
    }

    @Test
    fun `an unknown citation fails, and uncited sentences must stay under 5 percent`() {
        assertEquals(Verdict.FAIL, verdicts(state(1, reply = "It is a song [W9]."))[0])
        val cited = (1..19).map { state(1, reply = "It is a song [MB1].") }
        val uncited = state(1, reply = "It is a song.")
        assertEquals(Verdict.FAIL, Gate.evaluate(cited.take(18) + uncited, insightsAsked = true)[1].verdict)
        // Exactly 5 percent is not under it.
        assertEquals(Verdict.FAIL, Gate.evaluate(cited.take(19) + uncited, insightsAsked = true)[1].verdict)
        assertEquals(Verdict.PASS, Gate.evaluate(cited + cited.take(1) + uncited, insightsAsked = true)[1].verdict)
        assertEquals(Verdict.PASS, Gate.evaluate(cited, insightsAsked = true)[0].verdict)
    }
}
