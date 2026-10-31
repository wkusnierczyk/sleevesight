package app.sleevesight.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityRecord
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
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
import org.robolectric.Shadows.shadowOf

/**
 * The links from *AI services* and *Sources* to the keys (#539): under each service that needs a
 * key, "Set your … API key here." ("Update your …" once it is saved, #667), the key's name a link
 * that unfolds *Secrets* and lands on the key's row, scrolled into view and focused, with a key
 * saved or not; and *AI services*' fuller
 * text says a service switched off keeps its settings.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsKeyLinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, and read on the main thread (#622). The screen shows nothing until the settings
    // are read, and composed afresh ([restore]) it reads them again; the keys' states, once read,
    // add a note to each row that needs a key, which moves the rows below. From their files, those
    // reads ran on DataStore's own threads (and the keys' on `Dispatchers.IO`), which `waitForIdle`
    // does not wait for: on a busy machine the rows were not back yet when the test looked for them.
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inputModes: InputModeManager? = null

    @After
    fun close() {
        saves.cancel()
        // The accessibility manager outlives the test: leave no screen reader on for the next one.
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setTouchExplorationEnabled(false)
        manager.setEnabledAccessibilityServiceList(emptyList())
        manager.setEnabled(false)
    }

    private fun text(id: Int) = app.getString(id)

    private fun keyName(id: SecretId): String =
        text(
            when (id) {
                SecretId.ANTHROPIC -> R.string.settings_key_name_anthropic
                SecretId.OPENAI -> R.string.settings_key_name_openai
                SecretId.GOOGLE -> R.string.settings_key_name_google
                SecretId.LASTFM -> R.string.settings_key_name_lastfm
                SecretId.EXA -> R.string.settings_key_name_exa
            },
        )

    /** The sentence under a service that needs the key [id]: "Set your …", or "Update your …" once [saved] (#667). */
    private fun sentence(
        id: SecretId,
        saved: Boolean = false,
    ) = app.getString(if (saved) R.string.settings_update_key else R.string.settings_set_key, keyName(id))

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity (a rotation).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.settings_title))).assertExists("the settings composed again")
    }

    /** [restore], with the clock stopped, a frame for each step. */
    private fun restoreFrameByFrame() {
        val saved = registry.performSave()
        emitted = false
        compose.mainClock.advanceTimeByFrame()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.mainClock.advanceTimeByFrame()
    }

    /** Lets any landing run its course, the flash too. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** A node in the key [id]'s row that holds the focus. */
    private fun focusedIn(id: SecretId) = hasAnyAncestor(hasTestTag(SettingsTarget.key(id).tag)) and isFocused()

    private fun topOf(id: SecretId) =
        compose
            .onNodeWithTag(SettingsTarget.key(id).tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

    /** Turns on a screen reader that explores by touch, as TalkBack does, for Compose to report to. */
    private fun startScreenReader() {
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setEnabled(true)
        manager.setTouchExplorationEnabled(true)
        manager.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private fun show(
        section: SettingsSection,
        touch: Boolean = false,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // As on a phone being touched, where Compose's clickable rows take no focus of their
                // own; the test's input is otherwise taken as a keyboard's. Compose takes the mode
                // from the window's touch mode, which only the input pipeline sets.
                if (touch) enterTouchMode(activity.window.decorView)
                activity.setContent {
                    inputModes = LocalInputModeManager.current
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                        }
                    }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            if (touch) compose.runOnUiThread { assertEquals(InputMode.Touch, inputModes!!.inputMode) }
            compose.openSection(section)
            block()
        }
    }

    /** Puts [decor]'s window in touch mode, as a touch on the screen does. */
    private fun enterTouchMode(decor: View) {
        val root = decor.parent
        val ensure = root.javaClass.getDeclaredMethod("ensureTouchModeLocally", Boolean::class.javaPrimitiveType)
        ensure.isAccessible = true
        ensure.invoke(root, true)
        assertTrue(decor.isInTouchMode)
    }

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    /** Follows the link to the key [id] in the sentence under its service. */
    private fun follow(
        id: SecretId,
        stopClock: Boolean = false,
        saved: Boolean = false,
    ) {
        val target = SettingsTarget.key(id)
        // The sentence shows once the keys' states are read (#667).
        waitForNode(hasText(sentence(id, saved)))
        val sentence = compose.onNode(hasText(sentence(id, saved)), useUnmergedTree = true).performScrollTo()
        // With the clock stopped from the tap on, the landing goes no further than the test lets it.
        if (stopClock) compose.mainClock.autoAdvance = false
        sentence.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag }
    }

    /** The key [id]'s row is unfolded under *Secrets*, in view, and holds the focus. */
    private fun landedOn(id: SecretId) {
        val target = SettingsTarget.key(id)
        val focused = hasAnyAncestor(hasTestTag(target.tag)) and isFocused()
        waitForNode(focused)
        compose.onNode(focused).assertIsDisplayed()
        compose.onNodeWithTag(target.tag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun eachAiServiceLinksToItsKeyInSecrets() =
        show(SettingsSection.AI_SERVICES) {
            for (provider in listOf(ProviderId.ANTHROPIC, ProviderId.OPENAI, ProviderId.GOOGLE)) {
                val id = SecretId.forProvider(provider)!!
                follow(id)
                landedOn(id)
            }
        }

    @Test
    fun eachSourceThatNeedsAKeyLinksToItInSecrets() =
        show(SettingsSection.SOURCES) {
            for (id in listOf(SecretId.LASTFM, SecretId.EXA)) {
                follow(id)
                landedOn(id)
            }
        }

    @Test
    fun theLinkStaysOnceTheKeyIsSaved() {
        runBlocking { secrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
        show(SettingsSection.SOURCES) {
            // No note that the key is needed, but the way to it, which now updates it (#667).
            waitForNode(hasText(sentence(SecretId.LASTFM, saved = true)))
            assertTrue(compose.onAllNodes(hasText(sentence(SecretId.LASTFM))).fetchSemanticsNodes().isEmpty())
            val needs = app.getString(R.string.settings_needs_key_linked, text(R.string.settings_source_lastfm))
            assertTrue(compose.onAllNodes(hasText(needs, substring = true)).fetchSemanticsNodes().isEmpty())
            follow(SecretId.LASTFM, saved = true)
            landedOn(SecretId.LASTFM)
        }
    }

    @Test
    fun aLinkFollowedByTouchLandsAndThenLetsTheFocusGo() =
        show(SettingsSection.SOURCES, touch = true) {
            follow(SecretId.EXA)
            landedOn(SecretId.EXA)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            val focused = hasAnyAncestor(hasTestTag(SettingsTarget.key(SecretId.EXA).tag)) and isFocused()
            assertTrue(compose.onAllNodes(focused).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(SettingsTarget.key(SecretId.EXA).tag, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun aLinkFollowedFromAKeyboardLeavesTheFocusOnTheRow() =
        show(SettingsSection.SOURCES) {
            follow(SecretId.EXA)
            landedOn(SecretId.EXA)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            // To go on from: Enter opens the key's dialog, Tab goes to the next row.
            val focused = hasAnyAncestor(hasTestTag(SettingsTarget.key(SecretId.EXA).tag)) and isFocused()
            assertTrue(compose.onAllNodes(focused).fetchSemanticsNodes().isNotEmpty())
        }

    @Test
    fun aLandedRowRestsAQuarterOfTheWayDown() =
        show(SettingsSection.SOURCES) {
            follow(SecretId.LASTFM)
            landedOn(SecretId.LASTFM)
            compose.waitForIdle()
            val column = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
            // Not at the bottom edge, as the least scroll that shows it would leave it.
            assertEquals(column.top + column.height / 4, topOf(SecretId.LASTFM), 2f)
        }

    @Test
    fun aRowWithTheLinkSaysTheKeyIsNeededWithoutNamingSecrets() =
        show(SettingsSection.SOURCES) {
            val lastFm = text(R.string.settings_source_lastfm)
            waitForNode(hasText(app.getString(R.string.settings_needs_key_linked, lastFm), substring = true))
            // The link under it says where the key is.
            val named = app.getString(R.string.settings_needs_key, lastFm)
            assertTrue(compose.onAllNodes(hasText(named, substring = true)).fetchSemanticsNodes().isEmpty())
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tabRightAfterALandingScrollsAsUsual() =
        show(SettingsSection.AI_SERVICES) {
            follow(SecretId.ANTHROPIC)
            landedOn(SecretId.ANTHROPIC)
            val landed = compose.onNodeWithTag(SettingsTarget.key(SecretId.ANTHROPIC).tag, useUnmergedTree = true)
            val top = landed.fetchSemanticsNode().boundsInRoot.top
            // Within the landing's flash: the next row, in view below, takes the focus, and the column
            // does not bring it to the landing's place.
            compose
                .onNode(hasAnyAncestor(hasTestTag(SettingsTarget.key(SecretId.ANTHROPIC).tag)) and isFocused())
                .performKeyInput { pressKey(Key.Tab) }
            waitForNode(hasAnyAncestor(hasTestTag(SettingsTarget.key(SecretId.OPENAI).tag)) and isFocused())
            compose.waitForIdle()
            assertEquals(top, landed.fetchSemanticsNode().boundsInRoot.top, 1f)
        }

    @Test
    fun aLandingStillScrollingLandsAgainOnceTheScreenIsRecreated() =
        show(SettingsSection.SOURCES) {
            // Frame by frame, so the screen is recreated (as by a rotation) before the landing has
            // scrolled.
            follow(SecretId.EXA, stopClock = true)
            restoreFrameByFrame()
            compose.mainClock.autoAdvance = true
            landedOn(SecretId.EXA)
        }

    @Test
    fun aRecreationAfterALandingAndATabLandsOnNothing() =
        show(SettingsSection.AI_SERVICES) {
            follow(SecretId.ANTHROPIC)
            landedOn(SecretId.ANTHROPIC)
            compose.onNode(focusedIn(SecretId.ANTHROPIC)).performKeyInput { pressKey(Key.Tab) }
            waitForNode(focusedIn(SecretId.OPENAI))
            val top = topOf(SecretId.ANTHROPIC)
            restore()
            settle()
            // No new scroll, and the landed row does not take the focus again.
            assertEquals(top, topOf(SecretId.ANTHROPIC), 1f)
            assertTrue(compose.onAllNodes(focusedIn(SecretId.ANTHROPIC)).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun leavingAndComingBackAfterALandingLandsOnNothing() =
        show(SettingsSection.SOURCES) {
            follow(SecretId.EXA)
            landedOn(SecretId.EXA)
            val top = topOf(SecretId.EXA)
            // Leaving for another screen (*Credits*) and coming back saves the settings' state and
            // composes them afresh from it, as this does; still within the landing's flash.
            restore()
            settle()
            assertEquals(top, topOf(SecretId.EXA), 1f)
            assertTrue(compose.onAllNodes(focusedIn(SecretId.EXA)).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun aDragDuringTheLandingsScrollLeavesTheFocusAlone() =
        show(SettingsSection.SOURCES) {
            follow(SecretId.EXA, stopClock = true)
            // A few frames in, the scroll has begun; the listener drags the other way.
            repeat(SCROLL_FRAMES) { compose.mainClock.advanceTimeByFrame() }
            compose.onNode(hasScrollAction()).performTouchInput { swipeDown(durationMillis = 200) }
            compose.mainClock.autoAdvance = true
            settle()
            assertTrue(compose.onAllNodes(focusedIn(SecretId.EXA)).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun aScreenReaderFocusesTheLandedRowWhichKeepsTheFocus() {
        startScreenReader()
        show(SettingsSection.SOURCES, touch = true) {
            val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
            val before = manager.sentAccessibilityEvents.size
            follow(SecretId.EXA)
            landedOn(SecretId.EXA)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            // Touch exploration follows the focus, so the row keeps it after the flash.
            val focused = hasAnyAncestor(hasTestTag(SettingsTarget.key(SecretId.EXA).tag)) and isFocused()
            waitForNode(focused)
            // The screen reader's own focus moved to the row: Compose does not move it with the
            // input focus, whose event comes from the whole view.
            val row = compose.onNode(focused).fetchSemanticsNode().id
            val told =
                manager.sentAccessibilityEvents
                    .drop(before)
                    .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED }
                    .map(::virtualSource)
            assertTrue("screen reader focus moved to $told, the row is $row", row in told)
        }
    }

    /** The virtual view, a semantics node's id, that [event] comes from (`AccessibilityRecord`'s hidden source id). */
    private fun virtualSource(event: AccessibilityEvent): Int {
        val source = AccessibilityRecord::class.java.getMethod("getSourceNodeId").invoke(event) as Long
        return (source shr VIRTUAL_ID_SHIFT).toInt()
    }

    @Test
    fun aSwitchedOffServiceIsSaidToKeepItsSettings() =
        show(SettingsSection.AI_SERVICES) {
            val kept = "Switching off a service does not erase the most recently used model and effort settings."
            waitForNode(hasText(kept, substring = true))
        }

    private companion object {
        // An accessibility node id holds its virtual view's id in its upper half.
        const val VIRTUAL_ID_SHIFT = 32

        // Frames into a landing's scroll, which takes several hundred milliseconds.
        const val SCROLL_FRAMES = 3
    }
}
