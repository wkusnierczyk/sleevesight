package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import app.sleevesight.app.ui.openSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Can’t see your player?* under *Players* (#758): a panel with its heading, the note in two
 * paragraphs and a link to the FAQ's answer, found by the search; and the section's own text, now
 * in two paragraphs.
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

    private fun settings(block: () -> Unit) {
        val settings = SettingsStore(stores.open("settings"))
        val secrets = SecretStore(stores.open("secrets"), testCipher())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
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

    private fun notes() = compose.onAllNodesWithTag(MISSING_PLAYER_NOTE_TAG).fetchSemanticsNodes().size
}
