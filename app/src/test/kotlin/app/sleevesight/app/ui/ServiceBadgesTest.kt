package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.BADGE_PREFIX
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The services' badges (#537): each service row under *Sources*, *Genres* and *AI services* starts
 * with a badge the size of a player's icon, showing the letter Home's chips and the citations use
 * (each AI service's initial), and is read by a screen reader as before, the badge skipped.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceBadgesTest {
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

    private fun show(
        section: SettingsSection,
        test: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(section)
            test()
        }
    }

    private val density get() = app.resources.displayMetrics.density

    /** Where the text [text] starts, in dp from the screen's left. */
    private fun start(text: String): Float =
        compose
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.left / density

    /** The bounds, in pixels, of the one badge [letter] in the switch row named by [name]. */
    private fun badgeIn(
        name: Int,
        letter: String,
    ): Rect {
        val title = app.getString(name)
        val rowBounds = compose.onNode(isToggleable() and hasText(title)).fetchSemanticsNode().boundsInRoot
        val badges =
            compose
                .onAllNodesWithTag(BADGE_PREFIX + letter, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { rowBounds.contains(it.boundsInRoot.center) }
        assertEquals("one $letter badge in the row $title", 1, badges.size)
        return badges.single().boundsInRoot
    }

    /**
     * The row named by [name] starts with the badge [letter], 40 dp a side as a player's icon, before
     * the name; and the row reads as before: its name first, no badge letter, no description.
     */
    private fun assertBadge(
        name: Int,
        letter: String,
    ) {
        val title = app.getString(name)
        val row = compose.onNode(isToggleable() and hasText(title)).performScrollTo()
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val badge = badgeIn(name, letter)
        assertEquals("$title's badge is 40 dp wide", ROW_MARK_DP, badge.width / density, 0.5f)
        assertEquals("$title's badge is 40 dp high", ROW_MARK_DP, badge.height / density, 0.5f)
        assertTrue("$title's badge starts its row", badge.left - rowBounds.left < 17 * density)

        row.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        val read = row.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals("$title's row is read by its name first", title, read.first())
        assertFalse("$title's row does not read its badge: $read", letter in read)
    }

    @Test
    fun eachSourceStartsWithItsCitationLetter() =
        show(SettingsSection.SOURCES) {
            assertBadge(R.string.settings_source_musicbrainz, "MB")
            assertBadge(R.string.settings_source_wikipedia, "W")
            assertBadge(R.string.settings_source_lastfm, "L")
            assertBadge(R.string.settings_engine_exa, "S")
            assertBadge(R.string.settings_source_model, "AI")
        }

    @Test
    fun eachGenreSourceStartsWithItsCitationLetter() =
        show(SettingsSection.GENRES) {
            assertBadge(R.string.settings_source_musicbrainz, "MB")
            // Last.fm's switch waits for its key and cannot be moved; its badge is there all the same.
            assertBadge(R.string.settings_source_lastfm, "L")
            // Nested under *Show genres*, which has no badge, as before: 16 dp in. Both in view, under
            // the search box, which stays above the list (#649).
            compose.onNode(isToggleable() and hasText(app.getString(R.string.settings_show_genres))).performScrollTo()
            val musicBrainz = badgeIn(R.string.settings_source_musicbrainz, "MB").left / density
            assertEquals(16f, musicBrainz - start(app.getString(R.string.settings_show_genres)), 0.5f)
        }

    @Test
    fun eachAiServiceStartsWithItsInitial() =
        show(SettingsSection.AI_SERVICES) {
            assertBadge(R.string.settings_ai_provider_anthropic, "A")
            assertBadge(R.string.settings_ai_provider_openai, "O")
            assertBadge(R.string.settings_ai_provider_google, "G")
        }

    @Test
    fun anAiServicesModelAndEffortStartAtItsName() =
        show(SettingsSection.AI_SERVICES) {
            for (provider in listOf(R.string.settings_ai_provider_anthropic, R.string.settings_ai_provider_google)) {
                val name = app.getString(provider)
                val effort = app.getString(R.string.settings_ai_effort_of, name)
                compose.onNode(hasText(effort)).performScrollTo()
                val at = start(name)
                assertEquals("$name's model row", at, start(app.getString(R.string.settings_ai_model_of, name)), 0.5f)
                assertEquals("$name's effort row", at, start(effort), 0.5f)
            }
        }

    private companion object {
        /** A player's icon's size under *Players* (#535), which the badges take. */
        const val ROW_MARK_DP = 40f
    }
}
