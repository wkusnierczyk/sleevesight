package app.sleevesight.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** The token limit grows with the effort, since the reasoning counts against it (#665). */
class CompletionRequestTest {
    @Test
    fun `low keeps 4096, medium doubles it, and high or the service's default quadruple it`() {
        assertEquals(
            mapOf(
                ReasoningEffort.MINIMAL to 4096,
                ReasoningEffort.LOW to 4096,
                ReasoningEffort.MEDIUM to 8192,
                ReasoningEffort.HIGH to 16384,
                null to 16384,
            ),
            (ReasoningEffort.entries + null).associateWith { CompletionRequest.maxTokensFor(it) },
        )
    }
}
