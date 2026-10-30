package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.openSection
import app.sleevesight.core.settings.Dismissable
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Can’t see your player?* under *Players* (#758): a panel with its heading, the note in two
 * paragraphs and a link to the FAQ's answer, found by the search; and the section's own text, now
 * in two paragraphs. Its *Hide once* puts it away until *Players* is opened again; its *Dismiss*
 * for good, with *Undo*, and neither the section nor the search shows it then (#759).
 */
@RunWith(RobolectricTestRunner::class)
class MissingPlayerNoteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun paragraphs(id: Int) = app.getString(id).split("\n\n")

    private val store by lazy { SettingsStore(stores.open("settings")) }

    private fun settings(block: () -> Unit) {
        val settings = store
        val secrets = SecretStore(stores.open("secrets"), testCipher())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            // The screen draws once the settings have loaded, off the main thread.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(SEARCH_FIELD_TAG).fetchSemanticsNodes().isNotEmpty() }
            block()
        }
    }

    @Test
    fun theNoteShowsUnderThePlayersInItsParagraphsWithItsLink() {
        settings {
            val title = app.getString(R.string.settings_players_missing_title)
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { count(title) == 1 }
            compose.onNodeWithTag(MISSING_PLAYER_NOTE_TAG).performScrollTo()
            val note = paragraphs(R.string.settings_players_missing)
            assertEquals(2, note.size)
            for (paragraph in note) assertEquals(paragraph, 1, count(paragraph))
            compose
                .onNodeWithContentDescription(app.getString(R.string.settings_players_missing_link_description))
                .assertHasClickAction()
        }
    }

    @Test
    fun theSectionTextIsTwoParagraphs() {
        settings {
            compose.openSection(SettingsSection.PLAYERS)
            val text = paragraphs(R.string.settings_players_long)
            assertEquals(2, text.size)
            compose.waitUntil(5_000) { count(text.last()) == 1 }
            assertEquals(1, count(text.first()))
        }
    }

    @Test
    fun theSearchFindsTheNoteByItsText() {
        settings {
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("video apps")
            compose.waitUntil(5_000) { notes() == 1 }
            val onHome = app.getString(R.string.settings_players_on_home)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(onHome)
            compose.waitUntil(5_000) { count(app.getString(R.string.settings_players_on_home_summary)) == 1 }
            assertEquals(0, notes())
        }
    }

    @Test
    fun dismissPutsTheNoteAwayForGoodWithAMessageAndUndo() {
        settings {
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { notes() == 1 }
            compose
                .onNodeWithTag(MISSING_PLAYER_NOTE_DISMISS_TAG)
                .performScrollTo()
                .assert(hasText(app.getString(R.string.settings_players_missing_dismiss)))
                .performClick()
            compose.waitUntil(5_000) { Dismissable.PLAYERS_NOTE in store.now().dismissed }
            compose.waitUntil(5_000) { notes() == 0 }
            val message = app.getString(R.string.settings_players_missing_dismissed)
            compose.waitUntil(5_000) { count(message) == 1 }
            compose.onNodeWithText(app.getString(R.string.undo)).performClick()
            compose.waitUntil(5_000) { store.now().dismissed.isEmpty() }
            compose.waitUntil(5_000) { notes() == 1 }
        }
    }

    @Test
    fun hideOnceHidesTheNoteUntilPlayersIsOpenedAgain() {
        settings {
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { notes() == 1 }
            compose
                .onNodeWithTag(MISSING_PLAYER_NOTE_HIDE_ONCE_TAG)
                .performScrollTo()
                .assert(hasText(app.getString(R.string.settings_players_missing_hide_once)))
                .performClick()
            compose.waitUntil(5_000) { notes() == 0 }
            // Nothing stored, and no message.
            assertTrue(store.now().dismissed.isEmpty())
            assertEquals(0, count(app.getString(R.string.settings_players_missing_dismissed)))
            compose.onNodeWithTag("${SettingsSection.PLAYERS.panelTag}:close").performScrollTo().performClick()
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { notes() == 1 }
        }
    }

    // The section stays open through a search started and cleared, and so does what it holds.
    @Test
    fun hideOnceLastsThroughASearchStartedAndCleared() {
        settings {
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(5_000) { notes() == 1 }
            compose.onNodeWithTag(MISSING_PLAYER_NOTE_HIDE_ONCE_TAG).performScrollTo().performClick()
            compose.waitUntil(5_000) { notes() == 0 }
            val onHome = app.getString(R.string.settings_players_on_home)
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("player")
            compose.waitUntil(5_000) { count(onHome) == 1 }
            assertEquals(0, notes())
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("")
            compose.waitForIdle()
            compose.waitUntil(5_000) { count(onHome) == 1 }
            assertEquals(0, notes())
        }
    }

    @Test
    fun aDismissedNoteIsNotShownNorFound() {
        runBlocking { store.dismiss(Dismissable.PLAYERS_NOTE) }
        settings {
            compose.openSection(SettingsSection.PLAYERS)
            val onHome = app.getString(R.string.settings_players_on_home)
            compose.waitUntil(5_000) { count(onHome) == 1 }
            assertEquals(0, notes())
            compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement("video apps")
            val none = app.getString(R.string.settings_search_none, "video apps")
            compose.waitUntil(5_000) { count(none) == 1 }
            assertEquals(0, notes())
        }
    }

    private fun notes() = compose.onAllNodesWithTag(MISSING_PLAYER_NOTE_TAG).fetchSemanticsNodes().size
}
