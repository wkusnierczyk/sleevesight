package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.AppGraph
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.idleUntil
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.LastFont
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.Roboto
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.typographyOf
import app.sleevesight.core.settings.FontChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** *Font* (#377): Roboto, bundled, by default; the system font as a setting. */
@RunWith(RobolectricTestRunner::class)
class FontSettingsTest {
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

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun Typography.styles() =
        listOf(
            displayLarge,
            displayMedium,
            displaySmall,
            headlineLarge,
            headlineMedium,
            headlineSmall,
            titleLarge,
            titleMedium,
            titleSmall,
            bodyLarge,
            bodyMedium,
            bodySmall,
            labelLarge,
            labelMedium,
            labelSmall,
        )

    @Test
    fun theAppFontSetsEveryStyleInRobotoAndTheSystemFontLeavesMaterialsDefault() {
        assertTrue(typographyOf(FontChoice.APP_FONT).styles().all { it.fontFamily == Roboto })
        assertEquals(Typography(), typographyOf(FontChoice.SYSTEM_FONT))
        assertTrue(typographyOf(FontChoice.SYSTEM_FONT).styles().none { it.fontFamily == Roboto })
    }

    @Test
    fun theSettingsRowSwitchesTheThemeToTheSystemFontAndBack() {
        var typography: Typography? = null
        val fonts = settings.settings.map { it.font }.stateIn(saves, SharingStarted.Eagerly, FontChoice.APP_FONT)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // The theme follows the stored settings, as `AppGraph.font` has it follow them.
                    SleevesightTheme(fonts) {
                        typography = MaterialTheme.typography
                        SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                    }
                }
            }
            val appFont = text(R.string.settings_font_app)
            val systemFont = text(R.string.settings_font_system)
            compose.openSection(SettingsSection.APPEARANCE)
            compose.waitUntil(5_000) { shown(appFont) }
            assertEquals(Roboto, typography?.bodyLarge?.fontFamily)

            // One tap on the choice's button in *Font*'s row (#637).
            compose.onNode(hasText(systemFont) and isSelectable()).performScrollTo().performClick()
            compose.waitUntil(5_000) { shown(systemFont) && typography?.bodyLarge?.fontFamily != Roboto }
            assertEquals(FontChoice.SYSTEM_FONT, settings.now().font)
            assertEquals(Typography(), typography)

            compose.onNode(hasText(appFont) and isSelectable()).performScrollTo().performClick()
            compose.waitUntil(5_000) { typography?.bodyLarge?.fontFamily == Roboto }
            assertEquals(FontChoice.APP_FONT, settings.now().font)
        }
    }

    @Test
    fun theThemeStartsInTheFontsCurrentValue() {
        // As after a rotation, or a return to the app, with *System font* stored: no frame in Roboto.
        val seen = mutableListOf<Typography>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(MutableStateFlow(FontChoice.SYSTEM_FONT)) { seen += MaterialTheme.typography }
                }
            }
            compose.waitForIdle()
            assertEquals(Typography(), seen.first())
        }
    }

    /** The font family [text] is laid out in, on the screen shown. */
    private fun familyOf(text: String): FontFamily? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts
            .single()
            .layoutInput.style.fontFamily
    }

    /** The font families of the texts laid out on the screen shown, letting no frame pass. */
    private fun families(): List<FontFamily?> {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))
            .fetchSemanticsNodes()
            .forEach { it.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) }
        return layouts.map { it.layoutInput.style.fontFamily }
    }

    /**
     * With the test clock held, frame by frame from the first composition until [title] is laid
     * out: no text is ever laid out in Roboto, not even for a frame before a later value arrives.
     */
    private fun neverInRobotoUntilShown(title: String) {
        repeat(MAX_FRAMES) { frame ->
            val families = families()
            assertTrue("Text in Roboto at frame $frame", families.none { it == Roboto })
            if (shown(title) && families.isNotEmpty()) return
            compose.mainClock.advanceTimeByFrame()
        }
        fail("$title not shown within $MAX_FRAMES frames")
    }

    /**
     * The graph's settings in memory ([MemoryStore]), changed on the test's thread, the main one
     * (#606): with their file, each wait for a stored font to reach [AppGraph.font] depended on
     * DataStore's own threads finishing within [idleUntil]'s real-time limit. The one failure seen
     * (in CI) was that wait timing out, before any activity was started.
     */
    @Test
    fun mainActivityStartsInTheStoredFontAndFollowsIt() {
        app.makeGraph = { AppGraph(it, settingsData = MemoryStore()) }
        val title = text(R.string.first_run_leaves_title)
        // *System font* stored and read by this process; no `LastFont` from an earlier one.
        runBlocking { app.graph.settings.update { it.copy(font = FontChoice.SYSTEM_FONT) } }
        idleUntil("the stored font read") { app.graph.font.value == FontChoice.SYSTEM_FONT }

        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            neverInRobotoUntilShown(title)
            compose.mainClock.autoAdvance = true

            runBlocking { app.graph.settings.update { it.copy(font = FontChoice.APP_FONT) } }
            compose.waitUntil(5_000) { familyOf(title) == Roboto }
            runBlocking { app.graph.settings.update { it.copy(font = FontChoice.SYSTEM_FONT) } }
            compose.waitUntil(5_000) { familyOf(title) != Roboto }

            // A new activity, as after a rotation or a return to the app, starts in it too.
            compose.mainClock.autoAdvance = false
            scenario.recreate()
            neverInRobotoUntilShown(title)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun aNewProcessStartsInTheFontLastReadThenFollowsTheSettings() {
        // As left by an earlier process, whose settings said *System font*; this one's say the default.
        LastFont(app).value = FontChoice.SYSTEM_FONT
        // The settings' first read held off: while a directory stands where their file goes, every
        // read fails, and is retried. Without it the read could land before `font` is first read:
        // DataStore reads on a thread of its own, and a read done before the collector suspends
        // resumes it at once, on the main thread, with no turn of the main looper.
        val file = app.preferencesDataStoreFile("settings")
        assertTrue(file.mkdirs())
        assertEquals(FontChoice.SYSTEM_FONT, app.graph.font.value)
        assertTrue(file.delete())
        idleUntil("the settings' first read") { app.graph.font.value == FontChoice.APP_FONT }
        assertEquals(FontChoice.APP_FONT, LastFont(app).value)
    }

    @Test
    fun theCreditsListTheFontWithItsLicence() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen(onClose = {}) } }
            compose.onNodeWithText(text(R.string.credits_roboto)).performScrollTo().assertIsDisplayed()
            // Roboto's, and the home guide's hand-written font's (#627).
            compose.onNodeWithText(text(R.string.credits_caveat)).performScrollTo().assertIsDisplayed()
            assertEquals(2, compose.onAllNodesWithText("SIL Open Font License 1.1").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theHandFontsLicenceShipsWithTheApp() {
        val licence =
            app.assets
                .open("licences/caveat-ofl.txt")
                .bufferedReader()
                .use { it.readText() }
        assertTrue(licence.startsWith("Copyright 2014 The Caveat Project Authors"))
        assertTrue(licence.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(licence.contains("PERMISSION & CONDITIONS"))
    }

    @Test
    fun theFontsLicenceShipsWithTheApp() {
        val licence =
            app.assets
                .open("licences/roboto-ofl.txt")
                .bufferedReader()
                .use { it.readText() }
        assertTrue(licence.startsWith("Copyright 2011 The Roboto Project Authors"))
        assertTrue(licence.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(licence.contains("PERMISSION & CONDITIONS"))
    }

    private companion object {
        /** About ten seconds of frames, at 16 ms. */
        const val MAX_FRAMES = 600
    }
}
