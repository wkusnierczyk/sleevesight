package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.ROW_TITLE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.DescriptionsOnReturn
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * *Foldable descriptions* and *Descriptions on return* under *Appearance* (#847): on a return to the
 * settings or to a section, *Folded* folds the descriptions again, *Unfolded* opens them, and *As
 * left* (the default) keeps them as left, across a restart too; the switch off shows everything in
 * full and disables the choice. A change of the choice waits for the next return (#874), so nothing
 * moves under the tap. *Restore dismissed messages* is the section's last row.
 *
 * Drawn as on a phone (native graphics): Robolectric's own graphics measure text too narrow for
 * anything to fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionReturnTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // What the phone keeps: a restart reads it with a new store ([restart]).
    private val file = MemoryStore()
    private var settings by mutableStateOf(SettingsStore(file))
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    // The screen, dropped and composed afresh with nothing saved: as when the settings are left
    // and opened again ([leaveAndComeBack]), or the app restarts ([restart]).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun leaveAndComeBack(restart: Boolean = false) {
        emitted = false
        compose.waitForIdle()
        if (restart) settings = SettingsStore(file)
        registry = SaveableStateRegistry(null) { true }
        emitted = true
        compose.waitForIdle()
        compose.openSection(SettingsSection.APPEARANCE)
    }

    private fun show(block: () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                        }
                    }
                }
            }
            compose.openSection(SettingsSection.APPEARANCE)
            block()
        }

    private val spin get() = text(R.string.settings_spin_logo_summary)
    private val intro get() = text(R.string.settings_intro_summary)

    private fun description(words: String): SemanticsNodeInteraction {
        val matcher = hasTestTag(DESCRIPTION_TAG) and hasText(words)
        compose.waitUntil(conditionDescription = "the description $words", timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(matcher).performScrollTo()
    }

    private fun isOpen(words: String): Boolean {
        val state = description(words).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        return state == text(R.string.settings_description_expanded)
    }

    private fun tap(words: String) {
        description(words).performClick()
        compose.waitForIdle()
    }

    /** Closes *Appearance* with its *Close* and opens it again with its chip. */
    private fun reopenSection() {
        val section = SettingsSection.APPEARANCE
        compose.onNodeWithTag(section.panelTag + ":close").performScrollTo().performClick()
        compose.waitForIdle()
        compose.openSection(section)
    }

    private fun returning(mode: DescriptionsOnReturn) =
        runBlocking {
            settings.update { it.copy(descriptionsOnReturn = mode) }
        }

    @Test
    fun foldedFoldsEveryDescriptionAgainOnReopeningASectionOrTheSettings() {
        returning(DescriptionsOnReturn.FOLDED)
        show {
            assertFalse(isOpen(spin))
            tap(spin)
            assertTrue(isOpen(spin))
            reopenSection()
            assertFalse("folded on opening the section again", isOpen(spin))
            tap(spin)
            leaveAndComeBack()
            assertFalse("folded on opening the settings again", isOpen(spin))
        }
    }

    @Test
    fun unfoldedOpensEveryDescriptionOnReopeningASectionOrTheSettingsStillFoldingWithATap() {
        returning(DescriptionsOnReturn.UNFOLDED)
        show {
            assertTrue(isOpen(spin))
            tap(spin)
            assertFalse("a tap folds it", isOpen(spin))
            reopenSection()
            assertTrue("open on opening the section again", isOpen(spin))
            tap(spin)
            leaveAndComeBack()
            assertTrue("open on opening the settings again", isOpen(spin))
        }
    }

    @Test
    fun asLeftIsTheDefaultStartsFoldedAndKeepsEachAsLeftOnReopeningAndAcrossARestart() {
        assertEquals(DescriptionsOnReturn.AS_LEFT, Settings().descriptionsOnReturn)
        show {
            assertFalse("the first visit starts folded", isOpen(spin))
            tap(spin)
            assertTrue(isOpen(spin))
            // Kept by its section and its row's name.
            compose.waitUntil(WAIT_MS) {
                settings.now().unfoldedDescriptions == listOf("APPEARANCE/${text(R.string.settings_spin_logo)}")
            }
            reopenSection()
            assertTrue(isOpen(spin))
            assertFalse(isOpen(intro))
            leaveAndComeBack()
            assertTrue(isOpen(spin))
            leaveAndComeBack(restart = true)
            assertTrue("as left, across a restart", isOpen(spin))
            assertFalse(isOpen(intro))
            tap(spin)
            compose.waitUntil(WAIT_MS) { settings.now().unfoldedDescriptions.isEmpty() }
            leaveAndComeBack(restart = true)
            assertFalse(isOpen(spin))
        }
    }

    /** Chooses [label] in *Descriptions on return*'s button group, and waits for it to be saved as [mode]. */
    private fun choose(
        label: Int,
        mode: DescriptionsOnReturn,
    ) {
        val returning = hasContentDescription(text(R.string.settings_descriptions_on_return), substring = true)
        compose.onNode(hasText(text(label)) and isSelectable() and returning).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { settings.now().descriptionsOnReturn == mode }
        compose.waitForIdle()
    }

    @Test
    fun aChangeOfTheChoiceLeavesTheDescriptionsOnScreenAsTheyAreUntilTheNextReturn() {
        show {
            // *As left*, a first visit: all folded.
            assertFalse(isOpen(spin))
            assertFalse(isOpen(intro))
            choose(R.string.settings_descriptions_unfolded, DescriptionsOnReturn.UNFOLDED)
            assertFalse("nothing opens under the tap", isOpen(spin))
            assertFalse(isOpen(intro))
            reopenSection()
            assertTrue("open on the next return", isOpen(spin))
            choose(R.string.settings_descriptions_as_left, DescriptionsOnReturn.AS_LEFT)
            assertTrue("nothing folds under the tap", isOpen(spin))
            reopenSection()
            assertFalse("as left, never tapped open, on the next return", isOpen(spin))
        }
    }

    @Test
    fun foldableDescriptionsOffShowsEveryDescriptionInFullAndDisablesTheChoice() {
        runBlocking { settings.update { it.copy(foldDescriptions = false) } }
        show {
            val folded = text(R.string.settings_descriptions_folded)
            // *Folded* and *As left* of *Descriptions on return*, not of the two on a new track (#522),
            // which name the same choices (#910).
            val returning = hasContentDescription(text(R.string.settings_descriptions_on_return), substring = true)
            compose.onNode(hasText(folded) and isSelectable() and returning).performScrollTo().assertIsNotEnabled()
            compose
                .onNode(
                    hasText(text(R.string.settings_descriptions_as_left)) and isSelectable() and returning,
                ).assertIsNotEnabled()
            assertEquals(
                "no description folds",
                0,
                compose.onAllNodesWithTag(DESCRIPTION_TAG).fetchSemanticsNodes().size,
            )
            // Read with its switch, and a tap on it toggles it, as before #847.
            compose.onNode(isToggleable() and hasText(spin)).assertExists()
            compose.onNode(hasText(spin), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().spinLogo }
        }
    }

    @Test
    fun theRowsComeRightAfterTheItemsOnANewTrackAndRestoreDismissedMessagesComesLast() =
        show {
            val titles =
                compose
                    .onAllNodes(
                        hasTestTag(ROW_TITLE_TAG) and hasAnyAncestor(hasTestTag(SettingsSection.APPEARANCE.panelTag)),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
            val keep = titles.indexOf(text(R.string.settings_items_on_new_track))
            assertTrue(keep >= 0)
            assertEquals(text(R.string.settings_fold_descriptions), titles[keep + 1])
            assertEquals(text(R.string.settings_descriptions_on_return), titles[keep + 2])
            assertEquals(text(R.string.settings_restore_dismissed), titles.last())
        }

    @Test
    fun theSettingsAreStoredUnderTheirKeysAndDeveloperModeLeavesThem() {
        val defaults = Settings()
        assertTrue(defaults.foldDescriptions)
        assertEquals(emptyList<String>(), defaults.unfoldedDescriptions)
        val set =
            Settings(
                foldDescriptions = false,
                descriptionsOnReturn = DescriptionsOnReturn.UNFOLDED,
                unfoldedDescriptions = listOf("APPEARANCE/Spin the logo while music plays", "SOURCES/MusicBrainz"),
            )
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(set, prefs)
        assertEquals(
            mutablePreferencesOf(
                booleanPreferencesKey("display.fold_descriptions") to false,
                stringPreferencesKey("display.descriptions_on_return") to "UNFOLDED",
                stringPreferencesKey("display.descriptions_unfolded") to
                    "APPEARANCE/Spin the logo while music plays\u001FSOURCES/MusicBrainz",
            ),
            prefs,
        )
        assertEquals(set, SettingsCodec.read(prefs))
        // Appearance settings: developer mode turned off leaves them.
        assertEquals(set, set.copy(developerMode = true).withoutDeveloperMode())
        // A file from before them reads the defaults.
        val before = SettingsCodec.read(mutablePreferencesOf(booleanPreferencesKey("display.intro") to false))
        assertTrue(before.foldDescriptions)
        assertEquals(DescriptionsOnReturn.AS_LEFT, before.descriptionsOnReturn)
        assertEquals(emptyList<String>(), before.unfoldedDescriptions)
    }
}
