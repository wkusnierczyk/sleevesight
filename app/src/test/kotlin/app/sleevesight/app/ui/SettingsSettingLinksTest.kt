package app.sleevesight.app.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings.Secure
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FirstRunAcknowledgement
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.session.SleevesightListenerService
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.ProviderId
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
import org.robolectric.Shadows.shadowOf

/**
 * The links from one setting to another that #581 adds: *AI service knowledge* under *Sources* to
 * *AI services*, *Don't repeat artist facts* under *Insights* to *New session idle trigger* under
 * *History*, and the settings opened from Home at a service's model row; each unfolds its section
 * and lands on its target. With no AI service on, *AI service knowledge* says so.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSettingLinksTest {
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

    private val modelSummary
        get() = app.getString(R.string.settings_source_model_summary, text(R.string.settings_section_ai))

    private val factsSummary
        get() =
            app.getString(
                R.string.settings_artist_facts_once_summary,
                text(R.string.settings_session_gap),
                text(R.string.settings_section_history),
            )

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity (a rotation),
    // or as the settings come back from *Credits*.
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

    /** Lets any landing run its course, the flash too. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** The settings screen, opened at [landOn], with [section] unfolded by a tap, if any. */
    private fun show(
        section: SettingsSection? = null,
        landOn: SettingsTarget? = null,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            SettingsScreen(
                                settings,
                                secrets,
                                emptyMap(),
                                { CompletableDeferred(true) },
                                saves,
                                landOn = landOn,
                            ) { it }
                        }
                    }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            section?.let { compose.openSection(it) }
            block()
        }
    }

    /** Follows the link to [target] in the row text [sentence]. */
    private fun follow(
        sentence: String,
        target: SettingsTarget,
    ) {
        waitForNode(hasText(sentence, substring = true))
        compose
            .onNode(hasText(sentence, substring = true), useUnmergedTree = true)
            .performScrollTo()
            .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag }
    }

    /** [target], a row, is unfolded, in view, and holds the focus. */
    private fun landedOnRow(target: SettingsTarget) {
        val focused = hasAnyAncestor(hasTestTag(target.tag)) and isFocused()
        waitForNode(focused)
        compose.onNode(focused).assertIsDisplayed()
        compose.onNodeWithTag(target.tag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aiServiceKnowledgeLinksToAiServices() =
        show(SettingsSection.SOURCES) {
            follow(modelSummary, SettingsTarget(SettingsSection.AI_SERVICES))
            // The section's heading takes the focus, and the section is unfolded under it.
            val heading = hasTestTag(SettingsSection.AI_SERVICES.tag) and isFocused()
            waitForNode(heading)
            compose.onNode(heading).assertIsDisplayed()
            waitForNode(hasText(app.getString(R.string.settings_ai_model_of, "Anthropic")))
        }

    @Test
    fun dontRepeatArtistFactsLinksToTheIdleTriggerInHistory() =
        show(SettingsSection.INSIGHTS) {
            follow(factsSummary, SettingsTarget(SettingsSection.HISTORY, "session_gap"))
            landedOnRow(SettingsTarget(SettingsSection.HISTORY, "session_gap"))
            val row = hasAnyAncestor(hasTestTag("settings_row_session_gap"))
            compose.onNode(row and hasText(text(R.string.settings_session_gap))).assertIsDisplayed()
        }

    @Test
    fun aiServiceKnowledgeSaysWhenNoAiServiceIsOn() {
        val none = text(R.string.settings_source_model_no_service)
        show(SettingsSection.SOURCES) {
            waitForNode(hasText(modelSummary, substring = true))
            assertEquals(1, count(hasText(none, substring = true)))
            // Switched on, there is a model to ask, and the row says nothing of it.
            runBlocking { settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) } }
            compose.waitUntil(timeoutMillis = 5_000) { count(hasText(none, substring = true)) == 0 }
            waitForNode(hasText(modelSummary, substring = true))
        }
    }

    @Test
    fun theSettingsOpenedAtAModelLandOnItsRow() =
        show(landOn = SettingsTarget(SettingsSection.AI_SERVICES, "model_GOOGLE")) {
            landedOnRow(SettingsTarget(SettingsSection.AI_SERVICES, "model_GOOGLE"))
            compose
                .onNode(
                    hasAnyAncestor(hasTestTag("settings_row_model_GOOGLE")) and
                        hasText(app.getString(R.string.settings_ai_model_of, "Google")),
                ).assertIsDisplayed()
        }

    @Test
    fun theSettingsOpenedAtReactiveLandOnItsRow() =
        // From the insight card's menu, *See Settings for details* (#615).
        show(landOn = SettingsTarget.reactive) {
            landedOnRow(SettingsTarget.reactive)
            compose
                .onNode(
                    hasAnyAncestor(hasTestTag(SettingsTarget.reactive.tag)) and hasText("Reactive"),
                ).assertExists()
        }

    @Test
    fun theSettingsOpenedAtASettingLandOnceAVisit() {
        val target = SettingsTarget(SettingsSection.AI_SERVICES, "model_GOOGLE")
        show(landOn = target) {
            landedOnRow(target)
            settle()
            // Back at the top, then the screen recreated (a rotation, or back from *Credits*).
            val title = compose.onNode(hasText(text(R.string.settings_title))).performScrollTo()
            val top = title.fetchSemanticsNode().boundsInRoot.top
            restore()
            settle()
            // No landing again: the column stays where it was left, and the row takes no focus.
            val again = compose.onNode(hasText(text(R.string.settings_title))).assertIsDisplayed()
            assertEquals(top, again.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(0, count(hasAnyAncestor(hasTestTag(target.tag)) and isFocused()))
        }
    }

    @Test
    fun theSettingsRouteOfEarlierVersionsIsKeptSoASavedBackStackRestores() {
        firstRunDone()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity { activity ->
                val graph = activity.graphForTest()
                activity.setContent {
                    nav = rememberNavController()
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        nav,
                        settings = { Text(SETTINGS_DRAWN) },
                    )
                }
            }
            compose.waitForIdle()
            scenario.onActivity { nav.navigate(SettingsPage) }
            waitForNode(hasText(SETTINGS_DRAWN))
            // The destination 0.1.5 saved: a typed destination's id is the hash of its serial name,
            // and the object's route is that name alone, with no arguments.
            val name = SettingsPage.serializer().descriptor.serialName
            compose.runOnUiThread {
                assertEquals(name.hashCode(), nav.currentDestination?.id)
                assertEquals(name, nav.currentDestination?.route)
            }
            // A back stack saved with the settings on it is restored into a new controller.
            lateinit var saved: Bundle
            compose.runOnUiThread { saved = nav.saveState()!! }
            scenario.onActivity { activity ->
                val graph = activity.graphForTest()
                activity.setContent {
                    nav = rememberNavController().apply { restoreState(saved) }
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        nav,
                        settings = { Text(SETTINGS_RESTORED) },
                    )
                }
            }
            waitForNode(hasText(SETTINGS_RESTORED))
            compose.runOnUiThread { assertEquals(name, nav.currentDestination?.route) }
        }
    }

    /** As after the first run, with notification access on: the app opens on Home. */
    private fun firstRunDone() {
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
    }

    @Test
    fun homeOpensTheSettingsAtASettingThroughTheirRoute() {
        firstRunDone()
        val target = SettingsTarget(SettingsSection.AI_SERVICES, "model_OPENAI")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val graph = activity.graphForTest()
                activity.setContent {
                    SleevesightNavHost(
                        graph.notificationAccess,
                        graph.firstRun,
                        { true },
                        rememberNavController(),
                        settings = { links ->
                            SettingsScreen(
                                settings,
                                secrets,
                                emptyMap(),
                                { CompletableDeferred(true) },
                                saves,
                                landOn = links.landOn,
                            ) { it }
                        },
                        // Home, as a link to the setting, as its note of a model found unavailable is.
                        pipeline = { links ->
                            Text("Open", Modifier.testTag(OPEN).clickable { links.openSettingsAt(target) })
                        },
                    )
                }
            }
            waitForNode(hasTestTag(OPEN))
            compose.onNodeWithTag(OPEN).performClick()
            landedOnRow(target)
            assertEquals(0, count(hasTestTag(OPEN)))
        }
    }

    private fun MainActivity.graphForTest() = (application as SleevesightApplication).graph

    private companion object {
        const val OPEN = "open_settings_at"
        const val SETTINGS_DRAWN = "Settings drawn"
        const val SETTINGS_RESTORED = "Settings restored"
    }
}
