package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.Breakoff
import app.sleevesight.core.ai.BreakoffCause
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.synthesis.GroundingCounts
import app.sleevesight.core.synthesis.SelfRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The diagnostics lines of MusicBrainz's resolution name why it could not be asked (#191); the
 * grounding line, whether a self-correction was taken out (#597); a failed call's, how its reply broke
 * off (#665); a reply that revised itself, its sign (#798).
 */
class EventLineTest {
    @Test
    fun `an unreachable resolution names its failure, others nothing more`() {
        assertEquals(
            "resolved   a|song: unreachable (RateLimited) in 2050 ms",
            EventLine.of(PipelineEvent.Resolved("a|song", Outcome.Unreachable, 2050.milliseconds, "RateLimited")),
        )
        assertEquals(
            "resolved   a|song: song_article in 1123 ms",
            EventLine.of(PipelineEvent.Resolved("a|song", Outcome.SongArticle, 1123.milliseconds)),
        )
    }

    @Test
    fun `a preparation's line names its failure, and its second try`() {
        assertEquals(
            "prepared   a|song: unreachable (Offline) in 2008 ms",
            EventLine.of(PipelineEvent.Prepared("a|song", Outcome.Unreachable, 2008.milliseconds, "Offline")),
        )
        assertEquals(
            "prepared   a|song: song_article in 1100 ms, second try",
            EventLine.of(PipelineEvent.Prepared("a|song", Outcome.SongArticle, 1100.milliseconds, again = true)),
        )
    }

    @Test
    fun `a grounding line says when the model's correction of its own reply was taken out (#597)`() {
        val counts = GroundingCounts(ok = 1, model = 0, hedge = 0, uncited = 0, unknown = 0)
        assertEquals(
            "grounded   a|song: ok 1, model 0, hedge 0, uncited 0, unknown 0, self-correction taken out",
            EventLine.of(PipelineEvent.Grounded("a|song", counts, selfCorrected = true)),
        )
        assertEquals(
            "grounded   a|song: ok 1, model 0, hedge 0, uncited 0, unknown 0",
            EventLine.of(PipelineEvent.Grounded("a|song", counts)),
        )
    }

    @Test
    fun `a reply that revised itself says its sign, and what came of it (#798)`() {
        assertEquals(
            "insight    a|song: reply revised itself (aside), cut to what follows",
            EventLine.of(PipelineEvent.SelfRevised("a|song", SelfRevision.ASIDE, salvaged = true)),
        )
        assertEquals(
            "insight    a|song: reply revised itself (opening repeated), nothing usable",
            EventLine.of(PipelineEvent.SelfRevised("a|song", SelfRevision.REPEATED_OPENING)),
        )
        assertEquals(
            "insight    a|song: kept card revised itself (first person), nothing usable, not served",
            EventLine.of(PipelineEvent.SelfRevised("a|song", SelfRevision.FIRST_PERSON, cached = true)),
        )
        assertEquals(
            "insight    a|song: kept card revised itself (aside), cut to what follows",
            EventLine.of(PipelineEvent.SelfRevised("a|song", SelfRevision.ASIDE, salvaged = true, cached = true)),
        )
    }

    @Test
    fun `a failed call's line says how its reply broke off, the tokens so far, how much came and when (#665)`() {
        val breakoff = Breakoff(BreakoffCause.CONNECTION_LOST, Usage(1534, 1), "SocketException")
        assertEquals(
            "model      a|song: failed, incomplete (connection lost, SocketException), 1534 in, 1 out so far, " +
                "412 characters received, 8123 ms",
            EventLine.of(
                PipelineEvent.ModelFailed("a|song", AiErrorKind.INCOMPLETE, breakoff, 412, 8123.milliseconds),
            ),
        )
        assertEquals(
            "model      a|song: failed, incomplete (no end), 0 in, 0 out so far, 0 characters received, 30 ms",
            EventLine.of(
                PipelineEvent.ModelFailed(
                    "a|song",
                    AiErrorKind.INCOMPLETE,
                    Breakoff(BreakoffCause.NO_END, Usage(0, 0)),
                    0,
                    30.milliseconds,
                ),
            ),
        )
        assertEquals(
            "model      a|song: failed, unauthorized",
            EventLine.of(PipelineEvent.ModelFailed("a|song", AiErrorKind.UNAUTHORIZED)),
        )
    }
}
