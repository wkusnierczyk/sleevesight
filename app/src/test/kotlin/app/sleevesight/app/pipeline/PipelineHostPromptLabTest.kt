package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptChoice
import app.sleevesight.core.synthesis.PromptChoices
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt lab switched off (#1388): an insight a lab variant wrote is not shown once the pipeline
 * is rebuilt without the lab's choices, whether the run's own or the card paid for, kept in the
 * ledger; Write is offered for the track as when no card is kept, with no call on its own, and a tap
 * writes it with the release prompt. Switched on again, the variant's card is shown again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostPromptLabTest : PipelineHostFixture() {
    private val variantB =
        PromptChoices(
            PromptKind.entries.associateWith { kind ->
                if (kind == PromptKind.KEY) {
                    PromptChoice("B", LabPrompts.variant(PromptKind.KEY, "B")!!.instructions)
                } else {
                    PromptChoice(LabPrompts.RELEASE, null)
                }
            },
        )

    private fun insight(): InsightState? =
        host.state.value.track
            ?.insight

    private fun variant(): String? = (insight() as? InsightState.Written)?.writtenBy?.variant

    // Whether the live pipeline is built without the lab's choices.
    private fun labOff(): Boolean {
        val choices =
            host.liveParts.value
                ?.ai
                ?.choices ?: return false
        return !choices.on
    }

    // Every view published from now on while the live pipeline is built without the lab's choices:
    // they are taken at each build, so until the rebuild is live the one built with them is.
    private fun watchedOnceRebuilt(): List<PipelineView> {
        val views = mutableListOf<PipelineView>()
        host.published = { if (labOff()) views += it }
        return views
    }

    // The pipeline rebuilt with [choices], as a switch of the lab rebuilds it.
    private fun TestScope.labSwitched(choices: PromptChoices) {
        labChoices = choices
        keyChanges.value++
        runCurrent()
        settle()
    }

    // Sundown's key insight written with the lab's variant B, on a tap.
    private fun TestScope.writtenWithB() {
        labChoices = variantB
        started()
        play("Sundown")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requests)
        assertEquals("B", variant())
    }

    // The lab switched off: B's insight never shown, Write offered, no call on its own.
    private fun TestScope.switchedOff() {
        val views = watchedOnceRebuilt()
        labSwitched(PromptChoices.NONE)

        assertTrue(
            "no variant shown",
            views.none { (it.track?.insight as? InsightState.Written)?.writtenBy?.variant == "B" },
        )
        assertFalse(insight() is InsightState.Written)
        assertFalse(host.state.value.cardShown)
        assertNotNull("Write offered", host.state.value.writeKey)
        assertEquals("no call on its own", 1, provider.requests)
    }

    @Test
    fun `switched off, the insight a variant wrote is not shown and Write writes it with the release prompt`() =
        runTest {
            writtenWithB()
            switchedOff()

            tap()
            settle()
            assertEquals(2, provider.requests)
            assertTrue(insight() is InsightState.Written)
            assertTrue(variant() == null || variant() == LabPrompts.RELEASE)
        }

    @Test
    fun `switched off under the most eager mode, the insight a variant wrote is not written again on its own`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            writtenWithB()
            switchedOff()
            settle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `switched on under the most eager mode, the release prompt's insight is not written again on its own`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            started()
            play("Sundown")
            settle()
            tap()
            settle()
            assertTrue(insight() is InsightState.Written)
            assertTrue(variant() == null || variant() == LabPrompts.RELEASE)
            val calls = provider.requests

            labSwitched(variantB)
            settle()
            assertEquals("no call on its own", calls, provider.requests)
            assertFalse("the release text not shown as the variant's", variant() == "B")
        }

    @Test
    fun `switched on again, the card the variant wrote is shown again`() =
        runTest {
            writtenWithB()
            switchedOff()
            labSwitched(variantB)

            assertEquals("B", variant())
            assertEquals(1, provider.requests)
        }
}
