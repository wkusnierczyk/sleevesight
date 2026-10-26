package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.idleUntil
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.KeyRow
import app.sleevesight.app.ui.settings.KeyTable
import app.sleevesight.app.ui.settings.PurgeKeysScreen
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.ShownKey
import app.sleevesight.app.ui.settings.endingOf
import app.sleevesight.app.ui.settings.keyLine
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.Secret
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
 * The key screens' table of keys (#542), on *Purge keys* and *Load keys*: *API Key* and *Ending*,
 * a row a key, named by its service; the ending, with no ellipsis, as code (monospace, in a blue of
 * the theme's lightness); a row read by a screen reader as one item, its ending character by
 * character. And *Purge keys*' words: what it will delete, and that it does not revoke the keys.
 */
@RunWith(RobolectricTestRunner::class)
class KeyTableTest {
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

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9) + "Zq9x"
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"

    private fun text(id: Int) = app.getString(id)

    private fun waitForText(text: String) =
        idleUntil("a node: $text") { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun node(text: String): SemanticsNode = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().single()

    private fun annotated(node: SemanticsNode): AnnotatedString =
        node.config.getOrNull(SemanticsProperties.Text)!!.single()

    /** Checks that a screen reader reads a row as [text], one item, with [ending] in it read verbatim. */
    private fun assertRow(
        text: String,
        ending: String,
    ) {
        waitForText(text)
        val row = annotated(node(text))
        val tts = row.getTtsAnnotations(0, row.length).single()
        assertEquals(VerbatimTtsAnnotation(ending), tts.item)
        val at = row.text.lastIndexOf(ending)
        assertEquals(at until at + ending.length, tts.start until tts.end)
    }

    /** The style of the cell drawing [ending]: code, read verbatim, and left to its row for a screen reader. */
    private fun assertCodeCell(ending: String): SpanStyle {
        val cell = node(ending)
        assertTrue(cell.config.contains(SemanticsProperties.HideFromAccessibility))
        val shown = annotated(cell)
        assertEquals(VerbatimTtsAnnotation(ending), shown.getTtsAnnotations(0, shown.length).single().item)
        val span = shown.spanStyles.single()
        assertEquals(0 until ending.length, span.start until span.end)
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        return span.item
    }

    private fun row(
        @StringRes line: Int,
        @StringRes service: Int,
        ending: String,
    ) = app.getString(line, text(service), ending)

    private fun show(content: @Composable () -> Unit): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.setContent(content = content) }
        }

    @Test
    fun theEndingIsTheLastFourCharactersWithNoEllipsis() {
        assertEquals("3e4f", endingOf(exaKey))
    }

    @Test
    fun purgeKeysSaysWhatItWillDeleteAndThatItDoesNotRevokeThem() {
        assertEquals("These saved keys will be deleted from this phone:", text(R.string.purge_keys_listed))
        val explained = text(R.string.purge_keys_more_explained)
        val revoke =
            "Purging keys from Sleevesight does not revoke them. " +
                "You can still use your API keys elsewhere if you keep another copy."
        assertTrue(explained, explained.endsWith(revoke))
    }

    @Test
    fun aLineWithoutTheEndingIsShownAsItIs() {
        show { Text(keyLine(R.string.key_unreadable, "Exa", "3e4f")) }.use {
            val line = app.getString(R.string.key_unreadable, "Exa")
            waitForText(line)
            assertTrue(annotated(node(line)).spanStyles.isEmpty())
        }
    }

    @Test
    fun purgeKeysListsTheKeysInATableOfTheirServicesAndEndings() {
        show {
            SleevesightTheme(darkTheme = false) {
                PurgeKeysScreen(
                    secrets = mapOf(SecretId.EXA to SecretState.SAVED, SecretId.ANTHROPIC to SecretState.SAVED),
                    ending = { id -> endingOf(if (id == SecretId.EXA) exaKey else anthropicKey) },
                    onPurge = { ResetResult(removed = true, sealDeleted = true, left = emptyMap()) },
                    onDismiss = {},
                )
            }
        }.use {
            // One item a row, by the service's name, not "Exa API key".
            val exaRow = row(R.string.key_ending, R.string.settings_key_recipient_exa, "3e4f")
            assertRow(exaRow, "3e4f")
            assertRow(row(R.string.key_ending, R.string.settings_ai_provider_anthropic, "Zq9x"), "Zq9x")
            assertCodeCell("3e4f")
            assertCodeCell("Zq9x")
            // The endings are in the *Ending* column, at its start, after the *API Key* column.
            val ending = node(text(R.string.key_table_ending)).boundsInRoot.left
            val name = node(text(R.string.key_table_name)).boundsInRoot.left
            assertTrue("$name, $ending", name < ending)
            for (cell in listOf("3e4f", "Zq9x")) assertEquals(ending, node(cell).boundsInRoot.left)
            assertEquals(name, node(exaRow).boundsInRoot.left)
        }
    }

    @Test
    fun aFailureThatLeftNoKeysShowsNoTable() {
        show {
            SleevesightTheme(darkTheme = false) {
                PurgeKeysScreen(
                    secrets = mapOf(SecretId.EXA to SecretState.SAVED),
                    ending = { endingOf(exaKey) },
                    // The entries were removed; the Keystore key was not deleted.
                    onPurge = { ResetResult(removed = true, sealDeleted = false, left = emptyMap()) },
                    onDismiss = {},
                )
            }
        }.use {
            val header = text(R.string.key_table_name)
            waitForText(header)
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitForText(text(R.string.purge_keys_seal_not_deleted))
            assertTrue(compose.onAllNodes(hasText(header)).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodes(hasText(text(R.string.key_table_ending))).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun loadKeysListsTheKeysReadAndSavedInTheTable() {
        runBlocking { secrets.set(SecretId.EXA, Secret("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f")) }
        val code = "sleevesight-keys:v1\nanthropic=$anthropicKey\nexa=$exaKey"
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(code) } }
        show {
            SettingsScreen(
                settings,
                secrets,
                emptyMap(),
                { CompletableDeferred(true) },
                saves,
                scanner = scanner,
            ) { it }
        }.use {
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            // Read: a new key and one that replaces a saved key.
            assertRow(row(R.string.setup_new, R.string.settings_ai_provider_anthropic, "Zq9x"), "Zq9x")
            assertRow(row(R.string.setup_replaces, R.string.settings_key_recipient_exa, "3e4f"), "3e4f")
            assertCodeCell("Zq9x")
            assertCodeCell("3e4f")

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.load_keys_saved_title))
            assertRow(row(R.string.key_ending, R.string.settings_ai_provider_anthropic, "Zq9x"), "Zq9x")
            assertRow(row(R.string.key_ending, R.string.settings_key_recipient_exa, "3e4f"), "3e4f")
            assertCodeCell("Zq9x")
            assertCodeCell("3e4f")
        }
    }

    @Test
    fun theEndingIsInALighterBlueOnADarkThemeOnAFaintFillOfIt() {
        val styles =
            listOf(false, true).associateWith { dark ->
                show {
                    SleevesightTheme(darkTheme = dark) { KeyTable(listOf(KeyRow(ShownKey(SecretId.EXA, "3e4f")))) }
                }.use {
                    waitForText("3e4f")
                    assertCodeCell("3e4f")
                }
            }
        val light = styles.getValue(false).color
        val dark = styles.getValue(true).color
        for (blue in listOf(light, dark)) assertTrue("$blue", blue.blue > blue.red && blue.blue > blue.green)
        assertTrue("$light, $dark", luminance(dark) > luminance(light))
        for (style in styles.values) {
            assertEquals(style.color, style.background.copy(alpha = 1f))
            assertTrue("${style.background}", style.background.alpha < FAINT)
        }
    }

    private fun luminance(color: Color) = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        /** Below this alpha, a fill is faint. */
        const val FAINT = 0.5f
    }
}
