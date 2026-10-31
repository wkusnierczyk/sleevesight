package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SEARCH_ANNOUNCEMENT_TAG
import app.sleevesight.app.ui.settings.SEARCH_CLEAR_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SEARCH_NONE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
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
 * The settings' search (#649): the box under the title finds sections by their names and
 * descriptions, and settings by their names, descriptions, values and choices, a player by its
 * name; only the sections found show, open, all of a section its name finds, else only the
 * settings found, the query marked in their names; *Close* still closes one; clearing the search
 * opens the sections as before it; nothing found says so; a screen reader hears how many match.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSearchTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, and read on the main thread, as `SettingsKeyLinksTest`'s (#622).
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // The screen's saved state, as an activity keeps it ([restore]).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) {
                                PLAYER_NAMES[it] ?: it
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { count(SettingsSection.PLAYERS.tag) == 1 }
            block()
        }
    }

    private fun search(query: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** The sections that show, as chips or panels. */
    private fun shownSections(): List<SettingsSection> =
        SettingsSection.entries.filter { count(it.tag) + count(it.panelTag) > 0 }

    private fun isOpen(section: SettingsSection) = count(section.panelTag) == 1

    /** The titles of the settings [section]'s panel shows. */
    private fun rowsOf(section: SettingsSection): List<String> =
        compose
            .onAllNodes(
                hasTestTag(ROW_TITLE_TAG) and hasAnyAncestor(hasTestTag(section.panelTag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun aSectionsNameFindsItOpenWithAllItsSettingsAndNoOtherSection() =
        show {
            search(text(R.string.settings_section_appearance).lowercase())
            assertEquals(listOf(SettingsSection.APPEARANCE), shownSections())
            assertTrue(isOpen(SettingsSection.APPEARANCE))
            assertEquals(
                listOf(
                    R.string.settings_theme,
                    R.string.settings_theme_button,
                    R.string.settings_font,
                    R.string.settings_layout,
                    R.string.settings_keep_sources,
                    R.string.settings_intro,
                    R.string.settings_restore_dismissed,
                ).map(::text),
                rowsOf(SettingsSection.APPEARANCE),
            )
            // Its fuller description shows, as the section is found whole.
            compose.onNode(hasText(text(R.string.settings_appearance_long))).assertExists()
        }

    @Test
    fun aSettingsDescriptionFindsItAloneInItsSection() =
        show {
            // A part of *Keep artist results*' description alone.
            search(text(R.string.settings_keep_artists_summary).take(KEEP_ARTISTS_WORDS))
            assertEquals(listOf(SettingsSection.HISTORY), shownSections())
            assertEquals(listOf(text(R.string.settings_keep_artists)), rowsOf(SettingsSection.HISTORY))
            // Not found whole: neither its fuller description nor its sub-headings show.
            assertEquals(
                0,
                compose.onAllNodes(hasText(text(R.string.settings_history_long))).fetchSemanticsNodes().size,
            )
            assertEquals(
                0,
                compose.onAllNodes(hasText(text(R.string.settings_cache_lifetimes))).fetchSemanticsNodes().size,
            )
        }

    @Test
    fun aSubHeadingShowsOverASettingFoundUnderIt() =
        show {
            // A source's cache lifetime, under *History*'s sub-heading on how long findings are kept.
            search(text(R.string.settings_cache_wikidata))
            assertTrue(text(R.string.settings_cache_wikidata) in rowsOf(SettingsSection.HISTORY))
            compose.onNode(hasText(text(R.string.settings_cache_lifetimes))).assertExists()
        }

    @Test
    fun aChoiceFindsTheSettingThatOffersIt() =
        show {
            // *Separate pages* is only a choice of *Layout*, not its value: the default is the other.
            search(text(R.string.settings_layout_pages))
            assertEquals(listOf(SettingsSection.APPEARANCE), shownSections())
            assertEquals(listOf(text(R.string.settings_layout)), rowsOf(SettingsSection.APPEARANCE))
            // *dark* finds *Theme*.
            search("dark")
            assertTrue(text(R.string.settings_theme) in rowsOf(SettingsSection.APPEARANCE))
            assertTrue(text(R.string.settings_font) !in rowsOf(SettingsSection.APPEARANCE))
        }

    @Test
    fun aValueFindsTheSettingSetToIt() =
        show {
            // *New session idle trigger*'s value, *30 minutes* by default, is among its choices too;
            // *Keep history for*'s *90 days* is its value alone among the rows of *History*.
            val value = app.resources.getQuantityString(R.plurals.settings_days, NINETY, NINETY)
            search(value)
            assertTrue(text(R.string.settings_keep_history) in rowsOf(SettingsSection.HISTORY))
            assertTrue(text(R.string.settings_clear_history) !in rowsOf(SettingsSection.HISTORY))
        }

    @Test
    fun aPlayersNameFindsItsRow() {
        runBlocking { settings.update { it.copy(followedPlayers = mapOf(GROOVEBOX to true)) } }
        show {
            search("groovebox")
            // The players are listed off the main thread (#579), which the clock does not wait for.
            compose.waitUntil(5_000) { "Groovebox" in rowsOf(SettingsSection.PLAYERS) }
            assertEquals(listOf(SettingsSection.PLAYERS), shownSections())
            assertEquals(listOf("Groovebox"), rowsOf(SettingsSection.PLAYERS))
        }
    }

    @Test
    fun theQueryIsMarkedInTheNamesFound() =
        show {
            search("eme")
            val theme =
                compose
                    .onNode(
                        hasTestTag(ROW_TITLE_TAG) and hasText(text(R.string.settings_theme)),
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val marks = theme.spanStyles.filter { it.item.background != Color.Unspecified }
            val at = theme.text.indexOf("eme", ignoreCase = true)
            assertEquals(listOf(at to at + 3), marks.map { it.start to it.end })
        }

    @Test
    fun closeClosesASectionWhileSearchingAndItsChipOpensItAgain() =
        show {
            search(text(R.string.settings_layout_pages))
            tap(SettingsSection.APPEARANCE.panelTag + ":close")
            assertTrue(!isOpen(SettingsSection.APPEARANCE))
            assertEquals(1, count(SettingsSection.APPEARANCE.tag))
            tap(SettingsSection.APPEARANCE.tag)
            assertTrue(isOpen(SettingsSection.APPEARANCE))
        }

    @Test
    fun clearingTheSearchOpensTheSectionsAsBeforeIt() =
        show {
            tap(SettingsSection.HISTORY.tag)
            search(text(R.string.settings_layout_pages))
            assertEquals(listOf(SettingsSection.APPEARANCE), shownSections())
            compose.onNodeWithTag(SEARCH_CLEAR_TAG).performClick()
            compose.waitForIdle()
            assertEquals(SettingsSection.entries - SettingsSection.DEVELOPER, shownSections())
            assertTrue(isOpen(SettingsSection.HISTORY))
            assertTrue(!isOpen(SettingsSection.APPEARANCE))
            // Every row of *History* again.
            assertTrue(text(R.string.settings_clear_history) in rowsOf(SettingsSection.HISTORY))
        }

    @Test
    fun theSearchStaysThroughARecreation() =
        show {
            search(text(R.string.settings_layout_pages))
            restore()
            compose.waitUntil(5_000) { count(SettingsSection.APPEARANCE.panelTag) == 1 }
            assertEquals(listOf(SettingsSection.APPEARANCE), shownSections())
        }

    @Test
    fun nothingFoundSaysSoAndAScreenReaderHearsIt() =
        show {
            search("zzqx")
            assertEquals(emptyList<SettingsSection>(), shownSections())
            val none = app.getString(R.string.settings_search_none, "zzqx")
            compose.onNodeWithTag(SEARCH_NONE_TAG).assert(hasText(none))
            compose.mainClock.advanceTimeBy(ANNOUNCED)
            compose.waitForIdle()
            compose
                .onNodeWithTag(SEARCH_ANNOUNCEMENT_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(none)))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        }

    @Test
    fun aScreenReaderReadsTheBoxBySearchSettingsAndHearsHowManySectionsMatch() =
        show {
            // Named by its placeholder, with no content description to stand for what is typed.
            compose
                .onNodeWithTag(SEARCH_FIELD_TAG)
                .assert(hasText(text(R.string.settings_search_hint)))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            search(text(R.string.settings_layout_pages))
            compose.mainClock.advanceTimeBy(ANNOUNCED)
            compose.waitForIdle()
            val one = app.resources.getQuantityString(R.plurals.settings_search_matches, 1, 1)
            compose
                .onNodeWithTag(SEARCH_ANNOUNCEMENT_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(one)))
        }

    @Test
    fun editsThatKeepTheCountAreAnnouncedOnceTheTypingPauses() =
        show {
            val announced = { searchAnnouncement() }
            compose.mainClock.autoAdvance = false
            try {
                // From the first keystroke the live region is there, saying nothing yet.
                typeWithoutWaiting("Separate p")
                assertEquals("", announced())
                compose.mainClock.advanceTimeBy(PAUSE_SHORT)
                // Typing on, the count unchanged: the wait starts again, nothing is said.
                typeWithoutWaiting("Separate pa")
                assertEquals(listOf(SettingsSection.APPEARANCE), shownSections())
                compose.mainClock.advanceTimeBy(PAUSE_SHORT)
                assertEquals("", announced())
                // Paused: said once.
                compose.mainClock.advanceTimeBy(ANNOUNCED)
                assertEquals(app.resources.getQuantityString(R.plurals.settings_search_matches, 1, 1), announced())
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    private fun typeWithoutWaiting(query: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun searchAnnouncement(): String =
        compose
            .onNodeWithTag(SEARCH_ANNOUNCEMENT_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .joinToString("")

    private companion object {
        const val GROOVEBOX = "org.example.groovebox"

        // A player's name as the phone shows it, which has nothing of its package name.
        val PLAYER_NAMES = mapOf(GROOVEBOX to "Groovebox")

        const val KEEP_ARTISTS_WORDS = 30
        const val NINETY = 90
        const val ANNOUNCED = 1_000L

        // Shorter than the pause before an announcement (0.7 s), twice longer than it together.
        const val PAUSE_SHORT = 400L

        const val FRAMES = 3
    }
}
