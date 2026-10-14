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

    private fun verdicts(vararg results: TrackResult) = Gate.evaluate(results.toList()).map { it.verdict }

    @Test
    fun `nothing to check is not checked, not passed`() {
        assertEquals(listOf(Verdict.NOT_CHECKED, Verdict.NOT_CHECKED, Verdict.NOT_CHECKED), verdicts(state(1)))
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
        assertEquals(Verdict.FAIL, Gate.evaluate(cited.take(18) + uncited)[1].verdict)
        assertEquals(Verdict.FAIL, Gate.evaluate(cited.take(19) + uncited)[1].verdict) // exactly 5 percent
        assertEquals(Verdict.PASS, Gate.evaluate(cited + cited.take(1) + uncited)[1].verdict)
        assertEquals(Verdict.PASS, Gate.evaluate(cited)[0].verdict)
    }
}
