package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.keyboard.KeyboardVectors
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.keyboard.KeyboardStatus
import app.sleevesight.app.ui.keyboard.waitingMessage
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
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

/**
 * *Show this phone's name to the computer* (#433): off by default, a switch under *Secrets*, and
 * what *Type on a computer* is given; and what the session says while it waits, as the computer's
 * chooser lists the phone.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneNameSettingTest {
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

    private fun waitForText(text: String) =
        compose.waitUntil(conditionDescription = "the text $text", timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    /** What each keyboard session was given for *Show this phone's name*, in order. */
    private val given = mutableListOf<Boolean>()

    /** A keyboard that notes whether it was to show the phone's name, and says so. */
    private val noting: ComputerKeyboard = { _, showName, _, onStop ->
        LaunchedEffect(Unit) { given += showName }
        KeyboardStatus(R.string.keyboard_connected, onStop)
    }

    /** A scanner that reads a new keyboard code at once, as the camera would on the page's. */
    private val scanning: QrScanner = { onScanned, _ ->
        LaunchedEffect(Unit) { onScanned(KeyboardVectors.freshCode()) }
    }

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        scanner = scanning,
                        keyboard = noting,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            block()
        }
    }

    // Opens the Last.fm key's dialog, starts a session, and closes the dialog once it runs.
    private fun typeOnAComputer() {
        compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_key_type)).performClick()
        waitForText(text(R.string.keyboard_connected))
        compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun offByDefaultTheSessionAdvertisesNoName() =
        show {
            compose.onNodeWithText(text(R.string.settings_show_phone_name)).performScrollTo().assertIsOff()
            typeOnAComputer()
            assertEquals(listOf(false), given)
        }

    @Test
    fun switchedOnUnderSecretsTheSessionShowsTheName() =
        show {
            val row = compose.onNodeWithText(text(R.string.settings_show_phone_name)).performScrollTo()
            row.performClick()
            compose.waitUntil(5_000) { settings.now().showPhoneName }
            row.assertIsOn()
            typeOnAComputer()
            assertEquals(listOf(true), given)
        }

    @Test
    fun waitingSaysHowTheComputerListsThePhone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Column {
                        KeyboardStatus(waitingMessage(showName = false, advertised = null)) {}
                        KeyboardStatus(waitingMessage(showName = true, advertised = "Pixel 9")) {}
                        KeyboardStatus(waitingMessage(showName = true, advertised = null)) {}
                    }
                }
            }
            // Off: the unknown device, and the setting that names it.
            waitForText(text(R.string.keyboard_waiting))
            // On: the name the chooser shows.
            waitForText(app.getString(R.string.keyboard_waiting_named, "Pixel 9"))
            // On, but the name did not fit: the unknown device, and why.
            waitForText(text(R.string.keyboard_waiting_name_not_fitted))
        }
    }
}
