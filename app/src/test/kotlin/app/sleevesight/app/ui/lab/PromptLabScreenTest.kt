package app.sleevesight.app.ui.lab

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.lab.PromptLab
import app.sleevesight.app.ui.home.CreditRow
import app.sleevesight.app.ui.theme.DEVELOPER_MARK_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.synthesis.LabMark
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The prompt lab on screen (#1003): *Compare* under an insight, in the *Debug build* mark, only
 * while developer mode is on; the versions marked and logged once their prompts are shown; the
 * credit naming the variant in use, in the mark; and the lab's settings choosing it.
 */
@RunWith(RobolectricTestRunner::class)
class PromptLabScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lab by lazy { PromptLab(folder.root, scope, Dispatchers.Unconfined) }

    private val provider =
        object : AiProvider {
            override val id = ProviderId.ANTHROPIC

            override suspend fun models(key: Secret): ModelList = throw UnsupportedOperationException()

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                flowOf(
                    CompletionEvent.Delta(reply(request)),
                    CompletionEvent.Done(Usage(1, 1), StopReason.END),
                )
        }

    // The request of the card shown, `card-1`.
    private val songRequest =
        CompletionRequest("claude-haiku-5-5", LabPrompts.release(PromptKind.SONG) + "\nTitle: Sundown", card = CARD)

    private fun reply(request: CompletionRequest) = "Written from " + LabPrompts.opening(request).takeLast(TAIL)

    private fun subject(card: String) = LabSubject(PromptKind.SONG, "Sundown", "Gordon Lightfoot", null, card)

    // The song's request sent through the lab, as its pipeline would.
    private fun written() =
        runBlocking { lab.through(provider, lab.choices()).stream(songRequest, Secret("k")).toList() }

    @After
    fun close() {
        scope.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun show(
        card: String = CARD,
        variant: String? = null,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(MutableStateFlow(FontChoice.APP_FONT), developerMarks = MutableStateFlow(true)) {
                        CompositionLocalProvider(LocalPromptLab provides lab) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                CreditRow("Written by Claude Haiku 5.5.", variant = variant)
                                LabCompare(subject(card))
                                PromptLabBlock(lab)
                            }
                        }
                    }
                }
            }
            block()
        }
    }

    @Test
    fun compareIsOfferedOnlyInDeveloperMode() {
        show {
            compose.waitForIdle()
            assertEquals(0, compose.onAllNodesWithTag(LAB_COMPARE_ACTION_TAG).fetchSemanticsNodes().size)
            lab.developerMode(true)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(LAB_COMPARE_ACTION_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            // The release prompt and the first two drafts take part until set.
            assertEquals(1, count("Compare 3 prompts · 3 paid calls"))
        }
    }

    @Test
    fun theVersionsAreMarkedAndLoggedOnceThePromptsAreShown() {
        lab.developerMode(true)
        written()
        show {
            compose.onNodeWithTag(LAB_COMPARE_ACTION_TAG).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(LAB_SHOW_TAG).fetchSemanticsNodes().isNotEmpty() }
            for (shown in 1..3) compose.onNodeWithTag(labVersionTag(shown)).assertExists()
            compose.onNodeWithTag(labVersionTag(4)).assertDoesNotExist()
            compose.onNodeWithTag(labMarkTag(2, LabMark.GOOD)).performScrollTo().performClick()
            compose.onNodeWithTag(labMarkTag(3, LabMark.BAD)).performScrollTo().performClick()
            assertTrue(lab.log.value.isEmpty())

            compose.onNodeWithTag(LAB_SHOW_TAG).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { lab.log.value.size == 1 }
            val logged = lab.log.value.single()
            assertEquals(PromptKind.SONG, logged.kind)
            assertEquals(listOf(null, LabMark.GOOD, LabMark.BAD), logged.versions.sortedBy { it.shown }.map { it.mark })
            compose.waitUntil(WAIT_MS) { count(text(R.string.lab_logged)) == 1 }
        }
    }

    @Test
    fun aComparisonIsRefusedForACardNoRequestWroteSinceTheAppStarted() {
        lab.developerMode(true)
        written()
        show(card = "a card read from the cache") {
            compose.onNodeWithTag(LAB_COMPARE_ACTION_TAG).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { count(text(R.string.lab_compare_unsent)) == 1 }
            compose.onNodeWithTag(labVersionTag(1)).assertDoesNotExist()
        }
    }

    @Test
    fun theCreditNamesTheVariantThatWroteTheTextInsideTheMarkWhateverTheLabIsSetToNow() {
        lab.developerMode(true)
        show(variant = "B") {
            compose.waitUntil(WAIT_MS) { count("Written by Claude Haiku 5.5 · prompt B.") == 1 }
            val marked =
                compose
                    .onAllNodes(
                        hasText("Written by Claude Haiku 5.5 · prompt B.") and
                            hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG)),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
            assertEquals(1, marked.size)
            // The lab switched: the text shown was still written by prompt B.
            lab.update { it.use(PromptKind.SONG, LabPrompts.RELEASE) }
            compose.waitForIdle()
            assertEquals(1, count("Written by Claude Haiku 5.5 · prompt B."))
        }
    }

    @Test
    fun aTextTheReleasePromptWroteHasThePlayBuildsCreditWhileAVariantIsInUse() {
        lab.developerMode(true)
        lab.update { it.use(PromptKind.SONG, "B") }
        show(variant = LabPrompts.RELEASE) {
            compose.waitForIdle()
            assertEquals(1, count("Written by Claude Haiku 5.5."))
            assertEquals(0, count("· prompt"))
        }
    }

    @Test
    fun theLabsSettingsChooseTheVariantAKindIsWrittenWith() {
        lab.developerMode(true)
        show {
            compose.onNodeWithTag(LAB_BLOCK_TAG).performScrollTo()
            compose.onNodeWithTag(labKindTag(PromptKind.SONG)).performScrollTo().performClick()
            compose.onNodeWithTag(labUseTag(PromptKind.SONG, "C")).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { lab.settings.value.inUse(PromptKind.SONG) == "C" }
            compose.onNodeWithTag(labCompareTag(PromptKind.SONG, LabPrompts.CUSTOM)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { LabPrompts.CUSTOM in lab.settings.value.compared(PromptKind.SONG) }
            compose.waitUntil(WAIT_MS) { count("Compare 4 prompts · 4 paid calls") == 1 }
        }
    }

    private companion object {
        const val TAIL = 20
        const val CARD = "card-1"
    }
}
