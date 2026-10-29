package app.sleevesight.app.ui

import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import app.sleevesight.app.idleUntil
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.LOAD_KEYS_WORD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.WORDS_LIFETIME
import app.sleevesight.core.keys.KeyWords
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.platform.JvmKeyCrypto
import app.sleevesight.core.platform.JvmResourceReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/**
 * *Load keys*' words (#590): five words from the list, shown first, which seal the setup code; a
 * sealed code opened with them, spent once opened, refused with a wrong word, after they
 * expired, or when damaged; new words; and the list's credit.
 */
@RunWith(RobolectricTestRunner::class)
class LoadKeysWordsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val list = KeyWords.load(JvmResourceReader)

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    // The code the scanner reads next, once set: read as the camera's step shows, or as it is set
    // while that step shows.
    private var code by mutableStateOf<String?>(null)
    private val scanner: QrScanner = { onScanned ->
        val next = code
        LaunchedEffect(next) {
            if (next != null) {
                code = null
                onScanned(next)
            }
        }
    }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForNode(matcher: SemanticsMatcher) =
        idleUntil("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    /** The words the screen shows, in order. */
    private fun shownWords(): List<String> =
        compose.onAllNodes(hasTestTag(LOAD_KEYS_WORD_TAG)).fetchSemanticsNodes().map { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString("")
        }

    private fun sealed(
        setup: String,
        words: List<String>,
    ) = SealedSetup.seal(setup, words, JvmKeyCrypto)

    /** Opens *Load keys* on the settings screen, at its words. */
    private fun show(block: ActivityScenario<MainActivity>.() -> Unit) {
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
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            scenario.block()
        }
    }

    /**
     * [ms] pass while the phone sleeps: the clock that counts deep sleep moves (Robolectric's
     * `SystemClock`, the main looper's), the coroutines' delays, on the test clock, do not.
     */
    private fun asleep(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** [ms] pass while the phone is awake: both clocks move. */
    private fun awake(ms: Long) {
        asleep(ms)
        compose.mainClock.advanceTimeBy(ms)
    }

    private fun next() {
        compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
        waitForText(text(R.string.load_keys_read_code))
    }

    @Test
    fun theWordsComeFirstFiveFromTheListWithTheAddressAndTheScreenIsSecure() =
        show {
            val words = shownWords()
            assertEquals(KeyWords.COUNT, words.size)
            assertEquals(KeyWords.COUNT, words.toSet().size)
            assertTrue(words.toString(), words.all { it in list.words })
            compose.onNodeWithText(text(R.string.load_keys_type)).assertExists()
            val minutes = WORDS_LIFETIME.inWholeMinutes.toInt()
            val expire = app.resources.getQuantityString(R.plurals.load_keys_words_expire, minutes, minutes)
            compose.onNodeWithText(expire).assertExists()
            // The camera is not yet asked for: its step comes after *Next*.
            assertTrue(!shown(text(R.string.load_keys_read_code)))
            val flags =
                ShadowDialog
                    .getLatestDialog()
                    .window!!
                    .attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

            val address = "sleevesight.app/api-keys"
            compose
                .onNodeWithContentDescription(
                    app.getString(R.string.load_keys_instructions_of, address),
                ).performClick()
            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals("https://$address", started?.dataString)
        }

    @Test
    fun newWordsReplaceTheWordsShown() =
        show {
            val first = shownWords()
            compose.onNodeWithText(text(R.string.load_keys_new_words)).performClick()
            idleUntil("new words") { shownWords().let { it.size == KeyWords.COUNT && it != first } }
        }

    @Test
    fun aSealedCodeOpensWithTheWordsShownAndTheWordsAreThenSpent() =
        show {
            val words = shownWords()
            val code = sealed("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=not-a-key", words)
            next()
            this@LoadKeysWordsTest.code = code
            // Partly good, so *Scan again* is offered; the clear code's note is not.
            waitForText(text(R.string.load_keys_save_these))
            assertTrue(!shown(text(R.string.load_keys_clear_code)))
            assertNull(stored(SecretId.ANTHROPIC))

            // The same code again: the words opened one already.
            compose.onNodeWithText(text(R.string.setup_scan_again)).performClick()
            waitForText(text(R.string.load_keys_read_code))
            this@LoadKeysWordsTest.code = code
            waitForText(text(R.string.load_keys_used_title))
            compose.onNodeWithText(text(R.string.load_keys_used)).assertExists()
            assertNull(stored(SecretId.ANTHROPIC))

            // New words, and a code sealed with them, which saves its key.
            compose.onNodeWithText(text(R.string.load_keys_new_words)).performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            val fresh = shownWords()
            assertNotEquals(words, fresh)
            val good = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", fresh)
            next()
            this@LoadKeysWordsTest.code = good
            waitForText(text(R.string.setup_title))
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(lastFmKey, stored(SecretId.LASTFM))
        }

    @Test
    fun aSealedCodeWithAWrongWordIsRefusedAndTheWordsCanBeShownAgain() =
        show {
            val words = shownWords()
            val wrong = words.dropLast(1) + list.words.first { it !in words }
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", wrong)
            waitForText(text(R.string.load_keys_wrong_words_title))
            compose.onNodeWithText(text(R.string.load_keys_wrong_words)).assertExists()
            assertNull(stored(SecretId.LASTFM))

            // The words are not spent by a mismatch: the same ones show again, and open the right code.
            compose.onNodeWithText(text(R.string.load_keys_show_words)).performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            assertEquals(words, shownWords())
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForText(text(R.string.setup_title))
        }

    @Test
    fun theWordsExpireAndASealedCodeScannedThenIsRefused() =
        show {
            val words = shownWords()
            next()
            awake(WORDS_LIFETIME.inWholeMilliseconds)
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForText(text(R.string.load_keys_expired_title))
            assertNull(stored(SecretId.LASTFM))

            compose.onNodeWithText(text(R.string.load_keys_new_words)).performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            assertNotEquals(words, shownWords())
        }

    @Test
    fun wordsOutlivedInDeepSleepAreRefusedAtTheScan() =
        show {
            val words = shownWords()
            next()
            asleep(WORDS_LIFETIME.inWholeMilliseconds)
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForText(text(R.string.load_keys_expired_title))
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun wordsOutlivedInDeepSleepAreHiddenWhenShownAgain() =
        show {
            next()
            asleep(WORDS_LIFETIME.inWholeMilliseconds)
            compose.onNodeWithText(text(R.string.load_keys_show_words)).performClick()
            waitForText(text(R.string.load_keys_words_expired))
            assertTrue(shownWords().isEmpty())
            assertTrue(!shown(text(R.string.load_keys_next)))
        }

    @Test
    fun expiredWordsAreHiddenAndOfferOnlyNewWords() =
        show {
            awake(WORDS_LIFETIME.inWholeMilliseconds)
            waitForText(text(R.string.load_keys_words_expired))
            assertTrue(shownWords().isEmpty())
            assertTrue(!shown(text(R.string.load_keys_next)))
            compose.onNodeWithText(text(R.string.load_keys_new_words)).performClick()
            idleUntil("new words") { shownWords().size == KeyWords.COUNT }
        }

    @Test
    fun theCreditsListTheWordListWithItsLicence() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen(onClose = {}) } }
            compose.onNodeWithText(text(R.string.credits_eff_words)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("CC BY 3.0 US", substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aDamagedSealedCodeSaysSo() =
        show {
            next()
            code = "sleevesight-keys:v2:AAAA"
            waitForText(text(R.string.load_keys_damaged_title))
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertExists()
        }
}
