package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.pipeline.ModelCheck
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.ROW_NOTE_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What folding leaves in full or marks (#847): a model found unavailable is said under its row in
 * full, never folded, and so are what *Saver*'s count does and *No players yet* (#874); and a
 * search's marks show in a description's paragraph with a link, the link still working.
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionStateLinesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    /** Anthropic's client, whose list no longer holds its default model. */
    private val anthropic =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList =
                ModelList.Listed(listOf(ModelInfo("claude-opus-5-5", "Claude Opus 5.5", null)))

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> = emptyFlow()
        }

    private fun show(
        modelCheck: ModelCheck? = null,
        installedPlayers: InstalledPlayers? = null,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SettingsScreen(
                    settings,
                    secrets,
                    emptyMap(),
                    { CompletableDeferred(true) },
                    saves,
                    modelCheck = modelCheck,
                    installedPlayers = installedPlayers,
                ) { it }
            }
        }
        block()
    }

    @Test
    fun aModelFoundUnavailableIsSaidInFullUnderItsRow() {
        runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-test")) }
        val check = ModelCheck(mapOf(ProviderId.ANTHROPIC to anthropic), { Secret("sk-test") }, saves)
        val default = checkNotNull(Settings.DEFAULT_MODELS[ProviderId.ANTHROPIC])
        runBlocking { check.check(ProviderId.ANTHROPIC, default) }
        compose.waitUntil(WAIT_MS) { check.unavailable.value[ProviderId.ANTHROPIC]?.model == default }
        show(check) {
            compose.openSection(SettingsSection.AI_SERVICES)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(ROW_NOTE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val note = compose.onNodeWithTag(ROW_NOTE_TAG, useUnmergedTree = true).performScrollTo()
            val said =
                note
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text
            // The whole sentence, whatever the model's name: Anthropic's, then the request to choose again.
            val template = app.getString(R.string.settings_model_not_available, "Anthropic", "\u0000")
            assertTrue(said, said.startsWith(template.substringBefore('\u0000')))
            assertTrue(said, said.endsWith(template.substringAfter('\u0000')))
            val layouts = mutableListOf<TextLayoutResult>()
            note
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            val layout = layouts.single()
            assertTrue("laid out in full, on more than one line", layout.lineCount > 1 && !layout.hasVisualOverflow)
            // Not a description that folds: no fold of its own.
            assertTrue(
                compose.onAllNodes(hasTestTag(DESCRIPTION_TAG) and hasText(said)).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun whatStopAftersCountDoesIsSaidInFullUnderItsRow() {
        runBlocking { settings.update { it.copy(pauseAutoWriteAfter = 5, lookUpInBackground = true) } }
        show {
            compose.openSection(SettingsSection.INSIGHTS)
            val said = app.resources.getQuantityString(R.plurals.settings_pause_auto_write_summary_count, 5, 5)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(hasText(said), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val line = compose.onNode(hasText(said), useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            line
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            val layout = layouts.single()
            assertTrue("laid out in full, on more than one line", layout.lineCount > 1 && !layout.hasVisualOverflow)
            // As the line under a button group: no fold of its own.
            assertTrue(
                compose.onAllNodes(hasTestTag(DESCRIPTION_TAG) and hasText(said)).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun noPlayersYetIsSaidInFull() {
        // None of the players stored is on the phone.
        val phone = InstalledPlayers(icon = { null }, visible = { false }, seen = MutableStateFlow(emptySet()))
        show(installedPlayers = phone) {
            compose.openSection(SettingsSection.PLAYERS)
            val said = text(R.string.settings_players_none)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(hasText(said), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(said), useUnmergedTree = true).performScrollTo()
            // A state, not a description: no fold of its own.
            assertTrue(
                compose.onAllNodes(hasTestTag(DESCRIPTION_TAG) and hasText(said)).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun aSearchMarksTheMatchInAParagraphWithALinkAndTheLinkStillWorks() =
        show {
            val query = "frozen"
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
            compose.waitForIdle()
            val linked = hasText(query, substring = true) and hasLink()
            compose.waitUntil(
                WAIT_MS,
            ) { compose.onAllNodes(linked, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNode(linked, useUnmergedTree = true).performScrollTo()
            val shown = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
            val marks = shown.spanStyles.filter { it.item.background.alpha > 0f }
            assertEquals(listOf(query), marks.map { shown.text.substring(it.start, it.end).lowercase() })
            node.performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag ==
                    SettingsTarget.aiServices.linkTag
            }
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(SettingsSection.AI_SERVICES.panelTag).fetchSemanticsNodes().isNotEmpty()
            }
        }

    private fun hasLink() =
        SemanticsMatcher("has a link") { node ->
            val texts = node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty()
            texts.any { it.getLinkAnnotations(0, it.length).isNotEmpty() }
        }
}
