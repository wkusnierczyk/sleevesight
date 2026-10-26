package app.sleevesight.app.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
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

    /** The settings screen, opened at [landOn], with [section] unfolded by a tap, if any. */
    private fun show(
        section: SettingsSection? = null,
        landOn: SettingsTarget? = null,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
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
    fun homeOpensTheSettingsAtASettingThroughTheirRoute() {
        // As after the first run, with notification access on: the app opens on Home.
        val listener = ComponentName(app, SleevesightListenerService::class.java)
        Secure.putString(app.contentResolver, "enabled_notification_listeners", listener.flattenToString())
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(listener, true)
        FirstRunAcknowledgement(app).acknowledge()
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
    }
}
