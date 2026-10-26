package app.sleevesight.core.pipeline

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.synthesis.GroundingCounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The diagnostics lines of MusicBrainz's resolution name why it could not be asked (#191); the
 * grounding line, whether a self-correction was taken out (#597).
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
}
