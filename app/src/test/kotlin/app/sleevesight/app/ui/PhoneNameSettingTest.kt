package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * *Share phone's name with Type on a computer* (#433, #544): off by default, a switch under
 * *Secrets*, its title in the text colour, its summary linking the page the computer opens, and
 * what *Type on a computer* is given; and what the session says while it waits, as the computer's
 * chooser lists the phone, naming the setting by its title.
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

    /** What each keyboard session was given for *Share phone's name*, in order. */
    private val given = mutableListOf<Boolean>()

    /** A keyboard that notes whether it was to show the phone's name, and says so. */
    private val noting: ComputerKeyboard = { _, showName, _, onStop ->
        LaunchedEffect(Unit) { given += showName }
        KeyboardStatus(R.string.keyboard_connected, onStop)
    }

    /** A scanner that reads a new keyboard code at once, as the camera would on the page's. */
    private val scanning: QrScanner = { onScanned ->
        LaunchedEffect(Unit) { onScanned(KeyboardVectors.freshCode()) }
    }

    /** The scheme the screen was drawn in. */
    private var scheme: ColorScheme? = null

    private fun show(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    scheme = MaterialTheme.colorScheme
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
            block(scenario)
        }
    }

    // Opens the Last.fm key's dialog, starts a session, and closes the dialog once it runs.
    private fun typeOnAComputer() {
        compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
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
    fun theTitleIsInTheTextColourNotTheErrorColour() =
        show {
            val title = text(R.string.settings_show_phone_name)
            compose.onNodeWithText(title).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(title, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts)
            val color = layouts.single().layoutInput.style.color
            val drawn = checkNotNull(scheme)
            assertNotEquals(drawn.error, color)
            assertEquals(drawn.onSurface, color)
        }

    @Test
    fun theSummaryLinksThePageTheComputerOpens() =
        show { scenario ->
            val address = "sleevesight.app/keyboard.html"
            compose.onNodeWithText(text(R.string.settings_show_phone_name)).performScrollTo()
            // The address, to type in on the computer.
            compose.onNodeWithText(address, useUnmergedTree = true).assertIsDisplayed()
            val link = app.getString(R.string.settings_open_page, address)
            compose.onNodeWithContentDescription(link).performScrollTo().performClick()
            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://$address", started?.dataString)
        }

    @Test
    fun theWaitingTextNamesTheSettingByItsTitle() {
        val title = text(R.string.settings_show_phone_name)
        assertTrue(text(R.string.keyboard_waiting), text(R.string.keyboard_waiting).contains("“$title”"))
    }

    @Test
    fun waitingSaysHowTheComputerListsThePhone() {
        val off = text(R.string.keyboard_waiting)
        val named = app.getString(R.string.keyboard_waiting_named, "Pixel 9")
        val dropped = text(R.string.keyboard_waiting_name_not_fitted)
        // Each case drawn alone, and only its line shown.
        var case by mutableStateOf(Pair(false, null as String?))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { KeyboardStatus(waitingMessage(case.first, case.second)) {} }
            }
            val cases =
                listOf(
                    // Off: the unknown device, and the setting that names it.
                    Triple(off, false, null),
                    // On: the name the chooser shows.
                    Triple(named, true, "Pixel 9"),
                    // On, but the name could not be advertised: the unknown device, and why.
                    Triple(dropped, true, null),
                    // Off, a name read or not: still the unknown device.
                    Triple(off, false, "Pixel 9"),
                )
            for ((shown, showName, advertised) in cases) {
                case = Pair(showName, advertised)
                compose.waitForIdle()
                for (line in listOf(off, named, dropped)) {
                    val count = compose.onAllNodes(hasText(line)).fetchSemanticsNodes().size
                    assertEquals("$line, for $showName and $advertised", if (line == shown) 1 else 0, count)
                }
            }
        }
    }
}
