package app.sleevesight.app.ui

import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import app.sleevesight.app.ui.settings.API_KEYS_URL
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
 * *Load keys*' intro and words (#590, #765): five words from the list, drawn at *Start*, which seal
 * the setup code; a sealed code opened with them, spent once opened, refused with a wrong word,
 * after they expired, or when damaged, the camera reading on; the words renewing by themselves at
 * the end of their lifetime, awake or asleep; new words; and the list's credit.
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
    // while that step shows, as the camera reads on after a code it refused.
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

    /** Opens *Load keys* on the settings screen, at its intro. */
    private fun showIntro(block: ActivityScenario<MainActivity>.() -> Unit) {
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
            waitForText(text(R.string.load_keys_start))
            scenario.block()
        }
    }

    /** Opens *Load keys* on the settings screen, past its intro, at its words. */
    private fun show(block: ActivityScenario<MainActivity>.() -> Unit) =
        showIntro {
            compose.onNodeWithText(text(R.string.load_keys_start)).performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            block()
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

    private val scanHow by lazy { text(R.string.load_keys_scan_how) }

    /** From the words to the camera. */
    private fun next() {
        compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
        waitForText(scanHow)
    }

    /** From the camera back to the words, which show. */
    private fun previous() {
        compose.onNodeWithText(text(R.string.load_keys_previous)).performClick()
        waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
    }

    /** The line under the words, with [time] left. */
    private fun timeLeft(time: String) =
        app.getString(R.string.load_keys_words_within, time) + "\n" + text(R.string.load_keys_words_forget)

    /** Waits for the panel under the camera titled [title], with [body]. */
    private fun waitForPanel(
        title: Int,
        body: Int,
    ) {
        waitForText(text(title))
        compose.onNodeWithText(text(body)).assertExists()
    }

    @Test
    fun theIntroShowsTheAddressAsPlainTextAndStartDrawsTheWords() =
        showIntro {
            compose.onNodeWithText(text(R.string.load_keys_intro_open)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_intro_start)).assertIsDisplayed()
            // The address in full, as text: not a link, which would open it on the phone.
            compose
                .onNodeWithText(API_KEYS_URL)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            // No words before *Start*.
            assertTrue(shownWords().isEmpty())
            val flags =
                ShadowDialog
                    .getLatestDialog()
                    .window!!
                    .attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

            compose.onNodeWithText(text(R.string.load_keys_start)).performClick()
            waitForNode(hasTestTag(LOAD_KEYS_WORD_TAG))
            val words = shownWords()
            assertEquals(KeyWords.COUNT, words.size)
            assertEquals(KeyWords.COUNT, words.toSet().size)
            assertTrue(words.toString(), words.all { it in list.words })
        }

    @Test
    fun theWordsStepIsNumberedAndCountsDownTheirTime() =
        show {
            val title = app.getString(R.string.load_keys_step, 1, text(R.string.load_keys_words_title))
            compose.onNode(hasText(title) and isHeading()).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_words_secret)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_words_give)).assertIsDisplayed()
            compose.onNodeWithText(timeLeft("5:00")).assertExists()
            awake(8_000)
            waitForText(timeLeft("4:52"))
            // The camera is not yet asked for: its step comes after *Next*.
            assertTrue(!shown(scanHow))
        }

    @Test
    fun newWordsReplaceTheWordsShown() =
        show {
            val first = shownWords()
            compose.onNodeWithText(text(R.string.load_keys_new_words)).performClick()
            idleUntil("new words") { shownWords().let { it.size == KeyWords.COUNT && it != first } }
        }

    @Test
    fun theWordsRenewByThemselvesAtTheEndOfTheirLifetime() =
        show {
            val first = shownWords()
            awake(WORDS_LIFETIME.inWholeMilliseconds)
            idleUntil("new words") { shownWords().let { it.size == KeyWords.COUNT && it != first } }
            // No expired state: new words, their time whole again, and *Next*.
            waitForText(timeLeft("5:00"))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsEnabled()
        }

    @Test
    fun aSealedCodeOpensWithTheWordsShownAndTheWordsAreThenSpent() =
        show {
            val words = shownWords()
            val code = sealed("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=not-a-key", words)
            next()
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()
            this@LoadKeysWordsTest.code = code
            waitForPanel(R.string.load_keys_read_title, R.string.load_keys_read_done)
            // Sealed: no warning that anyone who saw it can read the keys.
            assertTrue(!shown(text(R.string.load_keys_clear_title)))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsEnabled().performClick()
            // Partly good, so *Scan again* is offered; nothing is saved.
            waitForText(text(R.string.load_keys_not_scanned))
            assertNull(stored(SecretId.ANTHROPIC))

            // The same code again: the words opened one already.
            compose.onNodeWithText(text(R.string.setup_scan_again)).performClick()
            waitForText(scanHow)
            this@LoadKeysWordsTest.code = code
            waitForPanel(R.string.load_keys_used_title, R.string.load_keys_used)
            assertNull(stored(SecretId.ANTHROPIC))

            // New words, and a code sealed with them, which saves its key at *Complete*.
            previous()
            val fresh = shownWords()
            assertNotEquals(words, fresh)
            val good = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", fresh)
            next()
            this@LoadKeysWordsTest.code = good
            waitForText(text(R.string.load_keys_read_title))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(text(R.string.load_keys_save_question_one))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(text(R.string.load_keys_enable_question))
            assertNull(stored(SecretId.LASTFM))
            compose.onNodeWithText(text(R.string.load_keys_complete)).performClick()
            waitForText(text(R.string.load_keys_saved_title_one))
            assertEquals(lastFmKey, stored(SecretId.LASTFM))
        }

    @Test
    fun aSealedCodeWithAWrongWordIsRefusedAndTheCameraReadsTheNextCode() =
        show {
            val words = shownWords()
            val wrong = words.dropLast(1) + list.words.first { it !in words }
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", wrong)
            waitForPanel(R.string.load_keys_wrong_words_title, R.string.load_keys_wrong_words)
            assertNull(stored(SecretId.LASTFM))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()

            // The words are not spent by a mismatch: the camera reads on, and they open the right code.
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForText(text(R.string.load_keys_read_title))
            assertTrue(!shown(text(R.string.load_keys_wrong_words_title)))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsEnabled()
        }

    @Test
    fun previousAfterWrongWordsShowsTheSameWords() =
        show {
            val words = shownWords()
            val wrong = words.dropLast(1) + list.words.first { it !in words }
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", wrong)
            waitForText(text(R.string.load_keys_wrong_words_title))
            previous()
            assertEquals(words, shownWords())
        }

    @Test
    fun wordsThatExpireOnTheCameraAreRenewedAndACodeOfThemIsExpired() =
        show {
            val words = shownWords()
            next()
            awake(WORDS_LIFETIME.inWholeMilliseconds)
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForPanel(R.string.load_keys_expired_title, R.string.load_keys_expired)
            assertNull(stored(SecretId.LASTFM))

            // *Previous* shows the new words; a code of the old ones is now one of other words.
            previous()
            assertNotEquals(words, shownWords())
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForText(text(R.string.load_keys_wrong_words_title))
            assertNull(stored(SecretId.LASTFM))
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
    fun wordsOutlivedInDeepSleepAreRenewedWhenShownAgain() =
        show {
            val words = shownWords()
            next()
            asleep(WORDS_LIFETIME.inWholeMilliseconds)
            previous()
            idleUntil("new words") { shownWords().let { it.size == KeyWords.COUNT && it != words } }
        }

    @Test
    fun wordsRenewedWhileShownMakeACodeOfTheOldOnesOneOfOtherWords() =
        show {
            val words = shownWords()
            awake(WORDS_LIFETIME.inWholeMilliseconds)
            idleUntil("new words") { shownWords().let { it.size == KeyWords.COUNT && it != words } }
            next()
            code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            waitForPanel(R.string.load_keys_wrong_words_title, R.string.load_keys_wrong_words)
            assertTrue(!shown(text(R.string.load_keys_expired_title)))
        }

    @Test
    fun wordsSpentAndThenShownMakeTheCodeTheyOpenedOneOfOtherWords() =
        show {
            val words = shownWords()
            val code = sealed("sleevesight-keys:v1\nlastfm=$lastFmKey", words)
            next()
            this@LoadKeysWordsTest.code = code
            waitForText(text(R.string.load_keys_read_title))
            // Spent: *Previous* shows new words, which the code was not made with.
            previous()
            assertNotEquals(words, shownWords())
            next()
            this@LoadKeysWordsTest.code = code
            waitForPanel(R.string.load_keys_wrong_words_title, R.string.load_keys_wrong_words)
            assertTrue(!shown(text(R.string.load_keys_used_title)))
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
    fun aDamagedSealedCodeSaysSoAndTheCameraReadsOn() =
        show {
            next()
            code = "sleevesight-keys:v2:AAAA"
            waitForPanel(R.string.load_keys_damaged_title, R.string.load_keys_damaged)
            assertTrue(!shown(text(R.string.setup_scan_again)))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()
        }
}
