package app.sleevesight.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * *Spin the logo while music plays* and *Logo speed* (#848) under *Appearance*, after *Logo intro
 * at start*: on and 33⅓ rpm by default, stored as moved; the speed's line for the record it is; the
 * speed dimmed and fixed while the switch is off; and "33⅓ rpm" read in words. The rows are kept as
 * PNGs, light and dark, when `SLEEVESIGHT_PICTURES` names a directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoSpinSettingTest {
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

    private fun show(
        dark: Boolean = false,
        block: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent {
                SleevesightTheme(darkTheme = dark) {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
        }
        compose.openSection(SettingsSection.APPEARANCE)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(text(R.string.settings_spin_logo)).fetchSemanticsNodes().isNotEmpty()
        }
        block()
    }

    private fun spinSwitch() = compose.onNode(hasText(text(R.string.settings_spin_logo)) and isToggleable())

    private fun speed(option: Int) = compose.onNode(hasText(text(option)) and isSelectable())

    @Test
    fun onAt33RpmByDefaultAndAFileFromBeforeReadsSo() {
        assertEquals(true, Settings().spinLogo)
        assertEquals(LogoSpeed.RPM_33, Settings().logoSpeed)
        // As stored before the settings existed (#848): other settings, and neither of these.
        val before = SettingsCodec.read(mutablePreferencesOf(booleanPreferencesKey("display.intro") to false))
        assertEquals(true, before.spinLogo)
        assertEquals(LogoSpeed.RPM_33, before.logoSpeed)
    }

    @Test
    fun eachMovedIsStoredUnderItsKey() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(spinLogo = false, logoSpeed = LogoSpeed.RPM_45), prefs)
        assertEquals(
            mutablePreferencesOf(
                booleanPreferencesKey("display.spin_logo") to false,
                stringPreferencesKey("display.logo_speed") to "RPM_45",
            ),
            prefs,
        )
        assertEquals(Settings(spinLogo = false, logoSpeed = LogoSpeed.RPM_45), SettingsCodec.read(prefs))
    }

    @Test
    fun theSwitchTurnsTheSpinOffAndOnAndTheSpeedIsChosenWithItsLine() =
        show {
            val switch = spinSwitch().performScrollTo()
            switch.assertIsOn()
            speed(R.string.settings_logo_speed_33).assertIsSelected()
            compose.onNodeWithText(text(R.string.settings_logo_speed_33_detail)).assertExists()
            speed(R.string.settings_logo_speed_45).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().logoSpeed == LogoSpeed.RPM_45 }
            speed(R.string.settings_logo_speed_45).assertIsSelected()
            compose.onNodeWithText(text(R.string.settings_logo_speed_45_detail)).performScrollTo()
            keep("appearance-logo-45")
            spinSwitch().performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().spinLogo }
            spinSwitch().assertIsOff()
            spinSwitch().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().spinLogo }
        }

    @Test
    fun whileTheSwitchIsOffTheSpeedCannotBeChangedAndKeepsItsValue() {
        runBlocking { settings.update { it.copy(spinLogo = false, logoSpeed = LogoSpeed.RPM_45) } }
        show {
            spinSwitch().performScrollTo().assertIsOff()
            speed(R.string.settings_logo_speed_33).performScrollTo().assertIsNotEnabled()
            speed(R.string.settings_logo_speed_45).assertIsNotEnabled().assertIsSelected()
            speed(R.string.settings_logo_speed_33).performClick()
            compose.waitForIdle()
            assertEquals(LogoSpeed.RPM_45, settings.now().logoSpeed)
            keep("appearance-logo-off")
            spinSwitch().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().spinLogo }
            speed(R.string.settings_logo_speed_33).assertIsEnabled()
        }
    }

    @Test
    fun aScreenReaderReads33RpmInWords() =
        show {
            val read =
                speed(R.string.settings_logo_speed_33)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .joinToString()
            assertEquals(
                "${text(R.string.settings_logo_speed)}: 33 and a third rpm. " +
                    text(R.string.settings_logo_speed_33_detail),
                read,
            )
            val single =
                speed(R.string.settings_logo_speed_45)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .joinToString()
            assertTrue(single, single.startsWith("${text(R.string.settings_logo_speed)}: 45 rpm."))
        }

    @Test
    fun theRowsComeRightAfterTheLogoIntroLightAndDark() {
        for (dark in listOf(false, true)) {
            show(dark) {
                compose.onNodeWithText(text(R.string.settings_logo_speed_33_detail)).performScrollTo()
                keep("appearance-logo-on-${if (dark) "dark" else "light"}")
                val top = { id: Int ->
                    compose
                        .onNodeWithText(text(id))
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
                val intro = top(R.string.settings_intro)
                val spin = top(R.string.settings_spin_logo)
                val speed = top(R.string.settings_logo_speed)
                assertTrue("$intro < $spin < $speed", intro < spin && spin < speed)
            }
        }
    }

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun keep(name: String) {
        val dir = out ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
