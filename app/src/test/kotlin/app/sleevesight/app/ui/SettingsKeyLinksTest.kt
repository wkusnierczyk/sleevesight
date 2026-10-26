package app.sleevesight.app.ui

import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The links from *AI services* and *Sources* to the keys (#539): under each service that needs a
 * key, "Set your … API key here.", the key's name a link that unfolds *Secrets* and lands on the
 * key's row, scrolled into view and focused, with a key saved or not; and *AI services*' fuller
 * text says a service switched off keeps its settings.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsKeyLinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inputModes: InputModeManager? = null

    @After
    fun close() {
        saves.cancel()
        stores.close()
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

    private fun sentence(id: SecretId) = app.getString(R.string.settings_set_key, keyName(id))

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
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
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
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    /** Follows the link to the key [id] in the sentence under its service. */
    private fun follow(id: SecretId) {
        val target = SettingsTarget.key(id)
        compose
            .onNode(hasText(sentence(id)), useUnmergedTree = true)
            .performScrollTo()
            .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag }
    }

    /** The key [id]'s row is unfolded under *Secrets*, in view, and holds the focus. */
    private fun landedOn(id: SecretId) {
        val target = SettingsTarget.key(id)
        val focused = hasAnyAncestor(hasTestTag(target.tag)) and isFocused()
        waitForNode(focused)
        compose.onNode(focused).assertIsDisplayed()
        compose.onNodeWithTag(target.tag).assertIsDisplayed()
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
            waitForNode(hasText(sentence(SecretId.LASTFM)))
            // No note that the key is needed, but the way to it, which now changes it.
            val needs = app.getString(R.string.settings_needs_key_linked, text(R.string.settings_source_lastfm))
            assertTrue(compose.onAllNodes(hasText(needs, substring = true)).fetchSemanticsNodes().isEmpty())
            follow(SecretId.LASTFM)
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
            compose.onNodeWithTag(SettingsTarget.key(SecretId.EXA).tag).assertIsDisplayed()
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
            val row = compose.onNodeWithTag(SettingsTarget.key(SecretId.LASTFM).tag).fetchSemanticsNode().boundsInRoot
            // Not at the bottom edge, as the least scroll that shows it would leave it.
            assertEquals(column.top + column.height / 4, row.top, 2f)
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

    @Test
    fun aSwitchedOffServiceIsSaidToKeepItsSettings() =
        show(SettingsSection.AI_SERVICES) {
            val kept = "Switching off a service does not erase the most recently used model and effort settings."
            waitForNode(hasText(kept, substring = true))
        }
}
