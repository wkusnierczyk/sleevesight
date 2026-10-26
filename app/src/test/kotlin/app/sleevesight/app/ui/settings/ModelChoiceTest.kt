package app.sleevesight.app.ui.settings

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The models the model choice offers (#540): once the list is had, only those the key can use. */
class ModelChoiceTest {
    private val default = "claude-haiku-5-5"

    private fun model(
        id: String,
        offered: Boolean = true,
    ) = ModelInfo(id, id, null, offered)

    private fun listed(vararg models: ModelInfo) = ModelLoad.Listed(models.toList())

    @Test
    fun aDeprecatedModelIsNotOfferedForANewChoiceButStaysForTheOneThatUsesIt() {
        val load = listed(model(default), model("claude-opus-5-5"), model("claude-sonnet-4-5", offered = false))

        val fresh = modelChoice(ProviderId.ANTHROPIC, null, load)
        assertEquals(default, fresh.default)
        assertEquals(listOf("claude-opus-5-5"), fresh.others)
        assertNull(fresh.gone)

        val using = modelChoice(ProviderId.ANTHROPIC, "claude-sonnet-4-5", load)
        assertEquals(listOf("claude-opus-5-5", "claude-sonnet-4-5"), using.others)
        assertNull(using.gone)
    }

    @Test
    fun aSelectedModelNotListedIsSaidToBeUnavailableAndNotOffered() {
        val load = listed(model(default), model("claude-opus-5-5"))
        val earlier = modelChoice(ProviderId.ANTHROPIC, "claude-opus-4-1", load)
        assertEquals("claude-opus-4-1", earlier.gone)
        assertEquals(listOf("claude-opus-5-5"), earlier.others)
    }

    @Test
    fun aDefaultNotListedIsNotOfferedAndIsUnavailableWhenSelected() {
        val load = listed(model("claude-opus-5-5"))
        val choice = modelChoice(ProviderId.ANTHROPIC, null, load)
        assertNull(choice.default)
        assertEquals(default, choice.gone)
        assertEquals(listOf("claude-opus-5-5"), choice.others)
    }

    @Test
    fun withoutAListTheDefaultAndTheModelChosenEarlierStayOffered() {
        for (load in listOf(ModelLoad.NoKey, ModelLoad.Unavailable, ModelLoad.Failed(AiErrorKind.OFFLINE))) {
            val choice = modelChoice(ProviderId.ANTHROPIC, "claude-opus-4-1", load)
            assertEquals(load.toString(), default, choice.default)
            assertEquals(load.toString(), listOf("claude-opus-4-1"), choice.others)
            assertNull(load.toString(), choice.gone)
        }
    }
}
