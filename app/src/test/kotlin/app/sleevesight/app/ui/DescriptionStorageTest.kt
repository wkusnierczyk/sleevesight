package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.SEARCH_CLEAR_TAG
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.unfoldedIds
import app.sleevesight.core.settings.Settings
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What *As left* stores of the descriptions' folds (#847): nothing of a tap on a description a search
 * found; each description by its section and its row's name, so rows of one name in two sections,
 * and a provider's rows, keep their own; at most [Settings.UNFOLDED_DESCRIPTIONS_LIMIT], the oldest
 * going first.
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionStorageTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // What the phone keeps: a restart reads it with a new store ([restart]).
    private val file = MemoryStore()
    private var settings by mutableStateOf(memorySettings(file))
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    /** The app closed and opened again: a new store on what the phone kept, the screen afresh. */
    private fun restart(vararg sections: SettingsSection) {
        emitted = false
        compose.waitForIdle()
        settings = memorySettings(file)
        registry = SaveableStateRegistry(null) { true }
        emitted = true
        compose.waitForIdle()
        sections.forEach { compose.openSection(it) }
    }

    private fun show(
        vararg sections: SettingsSection,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                if (emitted) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
        }
        sections.forEach { compose.openSection(it) }
        block()
    }

    private val spin get() = text(R.string.settings_spin_logo_summary)

    private fun matcher(words: String) = hasTestTag(DESCRIPTION_TAG) and hasText(words)

    /** Whether the [index]th description holding [words] shows unfolded. */
    private fun isOpen(
        words: String,
        index: Int = 0,
    ): Boolean {
        compose.waitUntil(conditionDescription = "the description $words", timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher(words)).fetchSemanticsNodes().size > index
        }
        val node = compose.onAllNodes(matcher(words))[index].performScrollTo().fetchSemanticsNode()
        return node.config[SemanticsProperties.StateDescription] == text(R.string.settings_description_expanded)
    }

    private fun tap(
        words: String,
        index: Int = 0,
    ) {
        isOpen(words, index)
        compose.onAllNodes(matcher(words))[index].performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun search(query: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
        compose.waitForIdle()
    }

    private fun clearSearch() {
        compose.onNodeWithTag(SEARCH_CLEAR_TAG).performClick()
        compose.waitForIdle()
    }

    // Folds and unfolds the spin's description during a search for it, then clears the search.
    private fun tapTwiceInASearch() {
        search("comes to rest")
        assertTrue("found, it shows open", isOpen(spin))
        tap(spin)
        assertFalse(isOpen(spin))
        tap(spin)
        assertTrue(isOpen(spin))
        clearSearch()
    }

    @Test
    fun tapsOnADescriptionASearchFoundAreNotStoredFromFolded() =
        show(SettingsSection.APPEARANCE) {
            assertFalse(isOpen(spin))
            tapTwiceInASearch()
            assertFalse("folded, as left before the search", isOpen(spin))
            assertEquals(emptyList<String>(), settings.now().unfoldedDescriptions)
            restart(SettingsSection.APPEARANCE)
            assertFalse("and after a restart", isOpen(spin))
        }

    @Test
    fun tapsOnADescriptionASearchFoundAreNotStoredFromUnfolded() =
        show(SettingsSection.APPEARANCE) {
            tap(spin)
            assertTrue(isOpen(spin))
            val stored = listOf("APPEARANCE/${text(R.string.settings_spin_logo)}")
            compose.waitUntil(WAIT_MS) { settings.now().unfoldedDescriptions == stored }
            search("comes to rest")
            tap(spin)
            assertFalse(isOpen(spin))
            clearSearch()
            assertTrue("open, as left before the search", isOpen(spin))
            assertEquals(stored, settings.now().unfoldedDescriptions)
            restart(SettingsSection.APPEARANCE)
            assertTrue("and after a restart", isOpen(spin))
        }

    @Test
    fun rowsOfOneNameInTwoSectionsKeepTheirOwnFoldAcrossARestart() {
        // *MusicBrainz* is a row under *Sources* and under *Genres* (one string), with other descriptions.
        val source = text(R.string.settings_source_musicbrainz_summary)
        val genres = text(R.string.settings_labels_musicbrainz_summary)
        show(SettingsSection.SOURCES, SettingsSection.GENRES) {
            tap(source)
            assertTrue(isOpen(source))
            assertFalse("the other section's own", isOpen(genres))
            compose.waitUntil(WAIT_MS) { settings.now().unfoldedDescriptions.isNotEmpty() }
            restart(SettingsSection.SOURCES, SettingsSection.GENRES)
            assertTrue(isOpen(source))
            assertFalse(isOpen(genres))
        }
    }

    @Test
    fun eachProvidersRowKeepsItsOwnFoldAcrossARestart() {
        // Every provider's *… reasoning effort* row has the same description; its name tells them apart.
        val effort = text(R.string.settings_ai_effort_summary)
        show(SettingsSection.AI_SERVICES) {
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher(effort)).fetchSemanticsNodes().size >= 3 }
            tap(effort, 0)
            tap(effort, 1)
            compose.waitUntil(WAIT_MS) { settings.now().unfoldedDescriptions.size == 2 }
            assertTrue(isOpen(effort, 0) && isOpen(effort, 1))
            assertFalse(isOpen(effort, 2))
            restart(SettingsSection.AI_SERVICES)
            assertTrue("both, across a restart", isOpen(effort, 0) && isOpen(effort, 1))
            assertFalse(isOpen(effort, 2))
        }
    }

    @Test
    fun atMostTheLimitAreKeptTheOldestChangeGoingFirst() {
        val limit = Settings.UNFOLDED_DESCRIPTIONS_LIMIT
        val full = (1..limit).map { "SOURCES/row $it" }
        val added = unfoldedIds(full, "SOURCES/new", unfolded = true)
        assertEquals(limit, added.size)
        assertEquals("the oldest went", full.drop(1) + "SOURCES/new", added)
        // Unfolded again, a row is the latest change; folded, it goes.
        assertEquals(full - "SOURCES/row 1" + "SOURCES/row 1", unfoldedIds(full, "SOURCES/row 1", unfolded = true))
        assertEquals(full - "SOURCES/row 2", unfoldedIds(full, "SOURCES/row 2", unfolded = false))
    }
}
