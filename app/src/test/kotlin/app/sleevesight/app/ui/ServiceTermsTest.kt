package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.keyboard.KeyboardVectors
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.keyName
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

/** Each AI service's terms, told where its key is entered (#409). */
@RunWith(RobolectricTestRunner::class)
class ServiceTermsTest {
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

    private val notes =
        listOf(R.string.settings_terms_anthropic, R.string.settings_terms_openai, R.string.settings_terms_google)

    private fun show(
        scanner: QrScanner = { _, _ -> },
        keyboard: ComputerKeyboard = { _, _, _ -> },
        block: ActivityScenario<MainActivity>.() -> Unit,
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
                        scanner = scanner,
                        keyboard = keyboard,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            scenario.block()
        }
    }

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    /** Whether a node's click action is named [label], as a screen reader announces it. */
    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click labelled \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun eachAiServicesKeyDialogTellsItsTermsAndLinksToThem() {
        val services =
            listOf(
                Triple(
                    SecretId.ANTHROPIC,
                    R.string.settings_terms_anthropic,
                    "www.anthropic.com/legal/commercial-terms",
                ),
                Triple(SecretId.OPENAI, R.string.settings_terms_openai, "openai.com/policies/services-agreement/"),
                Triple(SecretId.GOOGLE, R.string.settings_terms_google, "ai.google.dev/gemini-api/terms"),
            )
        for ((id, note, address) in services) {
            show {
                compose.onNodeWithText(text(keyName(id))).performScrollTo().performClick()
                waitForText(text(note))
                compose.onNodeWithText(text(note)).assertIsDisplayed()
                for (other in notes - note) assertEquals("$id shows only its own terms", false, shown(text(other)))
                compose
                    .onNodeWithText(address)
                    .performScrollTo()
                    .assert(hasClickLabel(text(R.string.link_open)))
                    .assertHeightIsAtLeast(48.dp)
                    .performClick()

                var started: Intent? = null
                onActivity { started = shadowOf(it).nextStartedActivity }
                assertEquals(Intent.ACTION_VIEW, started?.action)
                assertEquals("https://$address", started?.dataString)
            }
        }
    }

    @Test
    fun withATypeOnAComputerSessionRunningTheTermsAndTheSessionScrollIntoView() =
        show(scanner = scanning(KeyboardVectors.freshCode()), keyboard = tall) {
            compose.onNodeWithText(text(R.string.settings_key_name_google)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_key_type)).performClick()
            waitForText(SESSION_END)
            compose.onNodeWithText(SESSION_END).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("ai.google.dev/gemini-api/terms").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_terms_google)).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun aSourcesKeyDialogTellsNoServicesTerms() =
        show {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            waitForText(text(R.string.settings_key_field))
            for (note in notes) assertEquals(false, shown(text(note)))
        }

    @Test
    fun aSetupCodeWithAnAiKeyPointsToItsTerms() =
        show(scanner = scanning("sleevesight-keys:v1\ngoogle=AQ.Ab8RN6" + "Lq0_x-Yz.9".repeat(5))) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.setup_title))
            compose.onNodeWithText(text(R.string.setup_terms)).assertIsDisplayed()
        }

    @Test
    fun aSetupCodeOfSourcesKeysOnlyDoesNot() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=0123456789abcdef0123456789abcdef")) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.setup_title))
            assertEquals(false, shown(text(R.string.setup_terms)))
        }

    /** A keyboard session taller than the screen, ending in a line of text, [SESSION_END]. */
    private val tall: ComputerKeyboard = { _, _, _ ->
        Column {
            Box(Modifier.height(1_200.dp))
            Text(SESSION_END)
        }
    }

    /** A scanner that reads [text] at once, as the camera would on a code held up to it. */
    private fun scanning(text: String): QrScanner = { onScanned, _ -> LaunchedEffect(Unit) { onScanned(text) } }

    private companion object {
        const val SESSION_END = "The end of the keyboard session"
    }
}
