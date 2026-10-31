package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.ReportButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The per-choice descriptions (#546, #550): *Reactive*'s choices each show a line in its dialog
 * saying what they do, in the same radio button as the choice's name, so a screen reader, which
 * reads the merged node, reads the description with its choice. *Report button*'s choices are a
 * button group in its row (#637): each radio button is read with the setting's name and its
 * description, the chosen one's description shows under the buttons and is announced as it
 * changes, and a search for another one's description shows that one too.
 */
@RunWith(RobolectricTestRunner::class)
class ChoiceDetailsSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(
        section: SettingsSection,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(section)
            block()
        }
    }

    // One radio button in the dialog holding both the choice's name and its description: the node
    // a screen reader focuses and reads (the merged tree, as TalkBack sees it).
    private fun assertDescribed(choices: List<Pair<Int, Int>>) {
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        for ((label, detail) in choices) {
            compose
                .onNode(hasText(text(label)) and hasText(text(detail)) and radio and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
        }
    }

    @Test
    fun reactiveChoicesAreEachDescribedAndReadWithTheirName() =
        show(SettingsSection.INSIGHTS) {
            compose.onNodeWithText(text(R.string.settings_write_automatically)).performScrollTo().performClick()
            assertDescribed(
                listOf(
                    R.string.settings_write_every_change to R.string.settings_write_every_change_detail,
                    R.string.settings_write_when_skip to R.string.settings_write_when_skip_detail,
                    R.string.settings_write_never to R.string.settings_write_never_detail,
                ),
            )
        }

    @Test
    fun reportButtonSaysWhatTheChosenChoiceDoesUnderItsButtons() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show(SettingsSection.DEVELOPER) {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_report_button))).fetchSemanticsNodes().isNotEmpty()
            }
            val choices =
                listOf(
                    R.string.settings_report_button_show to R.string.settings_report_button_show_detail,
                    R.string.settings_report_button_auto to R.string.settings_report_button_auto_detail,
                    R.string.settings_report_button_hide to R.string.settings_report_button_hide_detail,
                )
            // Each radio button read as "Report button: <choice>. <what it does>".
            val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
            for ((label, detail) in choices) {
                val read =
                    compose
                        .onNode(hasText(text(label)) and radio)
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.ContentDescription]
                assertEquals(listOf("${text(R.string.settings_report_button)}: ${text(label)}. ${text(detail)}"), read)
            }
            for ((label, detail) in choices) {
                compose.onNode(hasText(text(label)) and isSelectable()).performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(text(detail)).fetchSemanticsNodes().isNotEmpty() }
                // Only the chosen one's, announced as it changes.
                val line = compose.onNode(hasText(text(detail)) and !isSelectable()).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, line.config.getOrNull(SemanticsProperties.LiveRegion))
                for ((_, other) in choices.filter { it.second != detail }) {
                    compose.onNode(hasText(text(other), substring = true) and !isSelectable()).assertDoesNotExist()
                }
            }
        }
    }

    // A search for what a choice not chosen does finds the row, and shows that description too.
    @Test
    fun aSearchForAnotherChoicesDescriptionShowsIt() {
        runBlocking { settings.update { it.copy(developerMode = true, reportButton = ReportButton.HIDE) } }
        show(SettingsSection.DEVELOPER) {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_report_button))).fetchSemanticsNodes().isNotEmpty()
            }
            val always = text(R.string.settings_report_button_show_detail)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(always.substringBefore(" on "))
            compose.waitForIdle()
            compose
                .onNode(hasText("${text(R.string.settings_report_button_show)}: $always") and !isSelectable())
                .performScrollTo()
                .assertIsDisplayed()
            // The chosen one's as ever.
            compose
                .onNode(
                    hasText(text(R.string.settings_report_button_hide_detail)) and !isSelectable(),
                ).assertExists()
        }
    }
}
