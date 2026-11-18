package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.pipeline.ModelRefusal
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The model choice with models a call found refused with the saved key (#970): last, under "Refused
 * with your key. Select to try again.", each with why, which a screen reader reads after the id;
 * still to be chosen; the selected one, refused, said so over the list and under its row in the
 * words of how it was refused.
 */
@RunWith(RobolectricTestRunner::class)
class RefusedModelDialogTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val chosen = mutableListOf<String?>()

    private fun text(id: Int) = app.getString(id)

    private val listed =
        ModelLoad.Listed(listOf(DEFAULT, PREVIEW, FLASH, PRO).map { ModelInfo(it, NAMES.getValue(it), null, true) })

    private fun showDialog(
        current: String?,
        refused: Map<String, ModelRefusal>,
        gone: UnavailableModel? = null,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        ModelDialog(
                            ProviderId.GOOGLE,
                            current,
                            { listed },
                            { id -> NAMES[id] ?: id },
                            { model -> chosen += model },
                            {},
                            refused,
                            gone,
                        )
                    }
                }
            }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(NAMES.getValue(FLASH)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    /** The labels of the dialog's options, top to bottom. */
    private fun options(): List<String> =
        compose
            .onAllNodes(OPTION)
            .fetchSemanticsNodes()
            .map { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .first()
                    .text
            }

    private val defaultLabel get() = app.getString(R.string.settings_ai_model_default, NAMES.getValue(DEFAULT))

    @Test
    fun refusedModelsTheDefaultTooGoLastUnderTheirHeadingEachWithWhy() =
        showDialog(FLASH, mapOf(PREVIEW to ModelRefusal.NOT_SERVED, DEFAULT to ModelRefusal.ACCOUNT)) {
            assertEquals(
                listOf(NAMES.getValue(FLASH), NAMES.getValue(PRO), defaultLabel, NAMES.getValue(PREVIEW)),
                options(),
            )
            compose.onNodeWithText("Refused with your key. Select to try again.").assertExists()
            assertEquals("Refused with your key. Select to try again.", text(R.string.settings_model_refused_heading))
            // Shown "id · why"; read "id, why".
            compose.onNodeWithText("$PREVIEW · No longer served", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("$DEFAULT · Refused for your account", useUnmergedTree = true).assertExists()
            val spoken =
                compose
                    .onAllNodes(DESCRIBED, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { it.config[SemanticsProperties.ContentDescription] }
            assertTrue(spoken.toString(), "$PREVIEW, No longer served" in spoken)
            assertTrue(spoken.toString(), "$DEFAULT, Refused for your account" in spoken)
        }

    @Test
    fun aRefusedModelCanStillBeChosenTheDefaultAsNoChoice() =
        showDialog(FLASH, mapOf(PREVIEW to ModelRefusal.NOT_SERVED, DEFAULT to ModelRefusal.ACCOUNT)) {
            compose.onNodeWithText(NAMES.getValue(PREVIEW)).performScrollTo().performClick()
            compose.onNodeWithText(defaultLabel).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(PREVIEW, null), chosen)
        }

    @Test
    fun withNoneRefusedNoHeadingShows() =
        showDialog(FLASH, emptyMap()) {
            assertEquals(
                listOf(defaultLabel, NAMES.getValue(PREVIEW), NAMES.getValue(FLASH), NAMES.getValue(PRO)),
                options(),
            )
            val heading = compose.onAllNodesWithText(text(R.string.settings_model_refused_heading))
            assertTrue(heading.fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun theSelectedModelRefusedStaysSelectedAndIsSaidOverTheListInHowItWasRefused() {
        val gone = UnavailableModel(ProviderId.GOOGLE, PREVIEW, ModelRefusal.NOT_SERVED)
        showDialog(PREVIEW, mapOf(PREVIEW to ModelRefusal.NOT_SERVED), gone) {
            compose
                .onNodeWithText(
                    "The currently selected Google model Gemini 3 Pro Preview is no longer served. " +
                        "Please select another model or provider.",
                ).assertExists()
            compose.onNode(hasText(NAMES.getValue(PREVIEW)) and OPTION).assertIsSelected()
            assertEquals(NAMES.getValue(PREVIEW), options().last())
        }
    }

    @Test
    fun theRowNoteSaysHowTheModelWasRefused() {
        val notes = mutableListOf<String?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    for (refusal in listOf(ModelRefusal.NOT_SERVED, ModelRefusal.ACCOUNT, null)) {
                        val gone = UnavailableModel(ProviderId.OPENAI, "gpt-6.1-sol", refusal)
                        notes += modelUnavailable("OpenAI", gone) { "GPT-6.1 Sol" }
                    }
                }
            }
            compose.waitUntil(WAIT_MS) { notes.size == 3 }
        }
        val said = { how: String ->
            "The currently selected OpenAI model GPT-6.1 Sol $how. Please select another model or provider."
        }
        assertEquals(
            listOf(said("is no longer served"), said("is refused for your account"), said("is not available")),
            notes.take(3),
        )
    }

    @Test
    fun theRowNoteIsForTheSelectedModelOnly() {
        val unavailable = mapOf(ProviderId.GOOGLE to UnavailableModel(ProviderId.GOOGLE, PREVIEW, ModelRefusal.ACCOUNT))
        val selected = Settings().withModel(ProviderId.GOOGLE, PREVIEW)
        assertEquals(unavailable[ProviderId.GOOGLE], goneModel(ProviderId.GOOGLE, selected, unavailable))
        assertEquals(null, goneModel(ProviderId.GOOGLE, Settings().withModel(ProviderId.GOOGLE, FLASH), unavailable))
    }

    private companion object {
        val OPTION = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        val DESCRIBED = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
        val DEFAULT = Settings.DEFAULT_MODELS.getValue(ProviderId.GOOGLE)
        const val PREVIEW = "gemini-3-pro-preview"
        const val FLASH = "gemini-3.5-flash"
        const val PRO = "gemini-3.5-pro"
        val NAMES =
            mapOf(
                DEFAULT to "Default Model",
                PREVIEW to "Gemini 3 Pro Preview",
                FLASH to "Gemini 3.5 Flash",
                PRO to "Gemini 3.5 Pro",
            )
    }
}
