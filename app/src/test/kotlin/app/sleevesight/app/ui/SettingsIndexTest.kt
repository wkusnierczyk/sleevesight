package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings' search index (#649) against the screen: every setting a section draws, with
 * everything that only shows under some settings shown (developer mode, *Diagnostics*, *On track
 * skip (auto)*'s *Crossfade*, a key saved, a player), is found by its own name, in its section.
 * A row added to a section without its entry in the index fails here.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsIndexTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun rowsOf(section: SettingsSection): List<String> =
        compose
            .onAllNodes(
                hasTestTag(ROW_TITLE_TAG) and hasAnyAncestor(hasTestTag(section.panelTag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun everySettingDrawnIsFoundByItsNameInItsSection() {
        runBlocking {
            settings.update {
                it.copy(
                    developerMode = true,
                    showDiagnostics = true,
                    writeAutomatically = WriteAutomatically.WHEN_I_SKIP,
                    followedPlayers = mapOf("org.example.tunes" to true),
                )
            }
            secrets.set(SecretId.EXA, Secret("exa-key-for-the-index"))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { "Player $it" }
                }
            }
            compose.waitUntil(5_000) { count(SettingsSection.DEVELOPER.tag) == 1 }
            val drawn =
                SettingsSection.entries.associateWith { section ->
                    compose.onNodeWithTag(section.tag).performScrollTo().performClick()
                    compose.waitForIdle()
                    rowsOf(section)
                }
            assertTrue("rows drawn: $drawn", drawn.values.all { it.isNotEmpty() })
            for ((section, titles) in drawn) {
                for (title in titles) {
                    compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(title)
                    compose.waitForIdle()
                    assertEquals("${section.name}: $title", 1, count(section.panelTag))
                    assertTrue("${section.name}: $title", title in rowsOf(section))
                }
            }
        }
    }
}
