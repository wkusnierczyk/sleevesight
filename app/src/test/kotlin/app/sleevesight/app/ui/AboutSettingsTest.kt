package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
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
import org.robolectric.Shadows.shadowOf

/** *About*'s rows for the site's pages (#415, #416, #421, #444), each opened in the browser. */
@RunWith(RobolectricTestRunner::class)
class AboutSettingsTest {
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

    @Test
    fun theDocumentationRowOpensTheIndexOnTheSite() {
        assertRowOpens(R.string.settings_docs, "https://sleevesight.app/docs/")
    }

    @Test
    fun theSourceCodeRowOpensTheRepository() {
        assertRowOpens(R.string.settings_source_code, "https://github.com/wkusnierczyk/sleevesight")
    }

    @Test
    fun theLicenceRowOpensTheLicence() {
        assertRowOpens(R.string.settings_licence, "https://github.com/wkusnierczyk/sleevesight/blob/main/LICENSE")
    }

    private fun assertRowOpens(
        title: Int,
        url: String,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme {
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            compose.openSection(SettingsSection.ABOUT)
            val text = app.getString(title)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            // The address, to type in where no browser opens.
            compose.onNodeWithText(url.removePrefix("https://")).performScrollTo().assertIsDisplayed()
            val link = app.getString(R.string.settings_open_page, text)
            compose.onNodeWithContentDescription(link).performScrollTo().performClick()

            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals(url, started?.dataString)
        }
    }
}
