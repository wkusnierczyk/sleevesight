package app.sleevesight.cli

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
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
    fun `the tally leaves out a track with nothing to narrate, as the writer decides it`() {
        val release =
            EvidenceSet.of(
                listOf(EvidenceDraft(SourceId.PLAYER, Scope.RELEASE, "On the release \"X\".", null, at)),
                JvmHasher,
            )
        val results =
            listOf(
                state(1, reply = "A fact. [MB1]"),
                state(2).let { it.copy(state = it.state.copy(evidence = release, insight = InsightState.NoEvidence)) },
            )
        // Model knowledge off: the release alone had nothing to narrate, so no call was due.
        val off = Gate.evaluate(results, insightsAsked = true, allowModel = false).first()
        assertEquals(Verdict.PASS, off.verdict)
        assertTrue("1 of 1 insights written" in off.detail, off.detail)
        // Model knowledge on: the release alone is narrated, so the track counts.
        val on = Gate.evaluate(results, insightsAsked = true, allowModel = true).first()
        assertTrue("1 of 2 insights written" in on.detail, on.detail)
    }

    @Test
    fun `with model knowledge on, the release alone of a report with no artists is still left out`() {
        val release =
            EvidenceSet.of(
                listOf(EvidenceDraft(SourceId.PLAYER, Scope.RELEASE, "On the release \"X\".", null, at)),
                JvmHasher,
            )
        val titleFirst = Cli.nowPlaying(2).copy(artists = "")
        val results =
            listOf(
                state(1, reply = "A fact. [MB1]"),
                state(2).let {
                    it.copy(
                        state =
                            it.state.copy(
                                nowPlaying = titleFirst,
                                normalized = Normalizer(JvmAccentFolder).normalize(titleFirst),
                                evidence = release,
                                insight = InsightState.NoEvidence,
                            ),
                    )
                },
            )
        val on = Gate.evaluate(results, insightsAsked = true, allowModel = true).first()
        assertTrue("1 of 1 insights written" in on.detail, on.detail)
    }

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

    @Test
    fun `live replies' uncited sentences are judged over at least three passes, pooled`() {
        val cited = state(1, reply = "It is a song [MB1].")
        val uncited = state(1, reply = "It is a song.")

        // [count] passes of 20 one-sentence insights, the [withUncited] first of them with one uncited.
        fun passes(
            count: Int,
            withUncited: Int,
        ) = List(count) { pass ->
            (List(19) { cited } + if (pass < withUncited) uncited else cited).map { it.copy(pass = pass + 1) }
        }.flatten()

        fun uncitedOver(results: List<TrackResult>) =
            Gate.evaluate(results, insightsAsked = true, sample = Gate.Sample.LIVE)[1]

        // Three passes, pooled: 1 of 60 passes, whatever one pass alone had; 3 of 60 is 5 percent, and fails.
        val pooled = uncitedOver(passes(3, 1))
        assertEquals(Verdict.PASS, pooled.verdict, pooled.detail)
        assertTrue("1 of 60 sentences" in pooled.detail && "over 3 passes" in pooled.detail, pooled.detail)
        assertEquals(Verdict.FAIL, uncitedOver(passes(3, 3)).verdict)
        // Two passes with 2 of 40 uncited: three passes might still pass, so it is not judged.
        assertEquals(Verdict.NOT_CHECKED, uncitedOver(passes(2, 2)).verdict)
        assertEquals(Verdict.PASS, uncitedOver(passes(3, 2)).verdict)
        val wrong = listOf(state(1, reply = "It is a song [W9]."))
        val unknown = Gate.evaluate(wrong, insightsAsked = true, sample = Gate.Sample.LIVE)
        assertEquals(Verdict.FAIL, unknown[0].verdict)
    }

    @Test
    fun `one live pass fails when no further passes could bring it under 5 percent, and is not judged under that`() {
        val cited = state(1, reply = "It is a song [MB1].")
        val uncited = state(1, reply = "It is a song.")

        fun onePass(uncitedOf20: Int) =
            Gate.evaluate(
                List(20 - uncitedOf20) { cited } + List(uncitedOf20) { uncited },
                insightsAsked = true,
                sample = Gate.Sample.LIVE,
            )[1]

        // 3 of 20 is 3 of 60 at best over three passes: 5 percent, which is not under it.
        val at = onePass(3)
        assertEquals(Verdict.FAIL, at.verdict, at.detail)
        assertTrue("even if the others were fully cited" in at.detail, at.detail)
        // 2 of 20 could be 2 of 60: not judged yet, and the run does not fail on it.
        val under = onePass(2)
        assertEquals(Verdict.NOT_CHECKED, under.verdict, under.detail)
        assertFalse(under.incomplete)
        assertTrue("at least 3 passes" in under.detail, under.detail)
        // Every sentence uncited fails at once.
        assertEquals(Verdict.FAIL, onePass(20).verdict)
        // Replayed replies are judged on one pass.
        assertEquals(Verdict.FAIL, Gate.evaluate(List(19) { cited } + uncited, insightsAsked = true)[1].verdict)
    }

    @Test
    fun `a pass with no sentence to judge does not count toward the three`() {
        val cited = state(1, reply = "It is a song [MB1].")
        // One pass of cited insights, and two in which every reply was NO_EVIDENCE.
        val noEvidence =
            cited.copy(state = cited.state.copy(insight = InsightState.NoEvidence)).let { r ->
                listOf(r.copy(pass = 2), r.copy(pass = 3))
            }
        val result =
            Gate.evaluate(
                List(20) { cited.copy(pass = 1) } + noEvidence,
                insightsAsked = true,
                sample = Gate.Sample.LIVE,
            )[1]
        assertEquals(Verdict.NOT_CHECKED, result.verdict, result.detail)
        assertTrue("over 1 passes with sentences" in result.detail, result.detail)
    }

    @Test
    fun `three passes judge only with enough sentences among them`() {
        val cited = state(1, reply = "It is a song [MB1].")
        val uncited = state(1, reply = "It is a song.")

        // Passes of 20, 1 and 1 sentences, [uncitedCount] of the first pass's uncited: 22 sentences,
        // under the 50 a live sample needs, so the share is judged against 50 at best.
        fun thin(uncitedCount: Int) =
            Gate.evaluate(
                (List(20 - uncitedCount) { cited } + List(uncitedCount) { uncited }).map { it.copy(pass = 1) } +
                    cited.copy(pass = 2) + cited.copy(pass = 3),
                insightsAsked = true,
                sample = Gate.Sample.LIVE,
            )[1]

        // 1 of 22 is under 5 percent, but not judged on so few sentences.
        val one = thin(1)
        assertEquals(Verdict.NOT_CHECKED, one.verdict, one.detail)
        assertTrue("3 passes and 50 sentences" in one.detail, one.detail)
        // 3 of 22 could be 3 of 50 at best: 6 percent, which fails.
        val three = thin(3)
        assertEquals(Verdict.FAIL, three.verdict, three.detail)
        assertTrue("even if the others were fully cited" in three.detail, three.detail)
    }
}
