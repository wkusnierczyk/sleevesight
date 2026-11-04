package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleUntil
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.Completed
import app.sleevesight.app.ui.settings.LOAD_KEYS_WORD_TAG
import app.sleevesight.app.ui.settings.LoadKeysScreen
import app.sleevesight.app.ui.settings.WORDS_LIFETIME
import app.sleevesight.core.keys.KeyCrypto
import app.sleevesight.core.keys.KeyWords
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.platform.JvmKeyCrypto
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * *Load keys* while it opens a sealed code (#785), as picked in the mock-up: the heading *Opening
 * the code…* with the turning record before it on its line, over the camera's view since #765, no
 * progress bar, and the heading still a heading, announced as it shows; *Previous* and *Next* are
 * dimmed meanwhile. A code read while another opens is taken once that one is refused, and a code
 * whose words are renewed while it opens is refused as expired (#765).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class LoadKeysOpeningTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // Holds the opening at the key's derivation until released, so the step stays on screen.
    private val held = CountDownLatch(1)
    private val crypto =
        object : KeyCrypto by JvmKeyCrypto {
            override fun derive(
                password: ByteArray,
                salt: ByteArray,
                iterations: Int,
            ): ByteArray {
                held.await()
                return JvmKeyCrypto.derive(password, salt, iterations)
            }
        }

    // A code that reads as sealed: its rounds, a salt, a nonce and a tag, all of the right sizes.
    private val code =
        SealedSetup.PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(4).putInt(SealedSetup.ITERATIONS).array() +
                    ByteArray(SealedSetup.SALT_BYTES + SealedSetup.NONCE_BYTES + TAG_BYTES),
            )

    private val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(code) } }

    @Test
    fun theOpeningStepShowsTheRecordBeforeItsHeadingAndNoProgressBar() {
        val opening = app.getString(R.string.load_keys_opening)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.setContent {
                        LoadKeysScreen(
                            emptyMap(),
                            Settings(),
                            scanner,
                            { _, _ -> Completed(emptyList(), null) },
                            { null },
                            {},
                            {},
                            crypto = crypto,
                        )
                    }
                }
                compose.onNodeWithText(app.getString(R.string.load_keys_start)).performClick()
                compose.onNodeWithText(app.getString(R.string.load_keys_next)).performClick()
                idleUntil("the opening step") {
                    compose.onAllNodes(hasText(opening)).fetchSemanticsNodes().isNotEmpty()
                }
                val heading =
                    compose
                        .onNodeWithText(opening)
                        .assert(isHeading())
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                        .fetchSemanticsNode()
                        .boundsInRoot
                val records: List<Rect> =
                    compose
                        .onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .map { it.boundsInRoot }
                val record = records.single()
                // Before the heading, on its line, at its usual size (20 sp at the default font).
                assertTrue(record.right <= heading.left)
                assertTrue(record.center.y > heading.top && record.center.y < heading.bottom)
                assertEquals(20f * compose.density.density, record.width, 1f)
                val bars =
                    compose
                        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                        .fetchSemanticsNodes()
                        .filter {
                            it.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate
                        }
                assertEquals(0, bars.size)
                compose.onNodeWithText(app.getString(R.string.load_keys_previous)).assertIsNotEnabled()
                compose.onNodeWithText(app.getString(R.string.load_keys_next)).assertIsNotEnabled()
            } finally {
                held.countDown()
            }
        }
    }

    // Holds the first opening only, at its key's derivation, until [first] is released.
    private val first = CountDownLatch(1)
    private val derived = AtomicInteger()
    private val holdingFirst =
        object : KeyCrypto by JvmKeyCrypto {
            override fun derive(
                password: ByteArray,
                salt: ByteArray,
                iterations: Int,
            ): ByteArray {
                if (derived.getAndIncrement() == 0) first.await()
                return JvmKeyCrypto.derive(password, salt, iterations)
            }
        }

    @Test
    fun aCodeReadWhileAnotherOpensIsTakenOnceThatOneIsRefused() {
        var next by mutableStateOf<String?>(null)
        val reading: QrScanner = { onScanned ->
            val read = next
            LaunchedEffect(read) {
                if (read != null) {
                    next = null
                    onScanned(read)
                }
            }
        }
        val list = KeyWords.load(JvmResourceReader)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.setContent {
                        LoadKeysScreen(
                            emptyMap(),
                            Settings(),
                            reading,
                            { _, _ -> Completed(emptyList(), null) },
                            { null },
                            {},
                            {},
                            list = list,
                            crypto = holdingFirst,
                        )
                    }
                }
                compose.onNodeWithText(app.getString(R.string.load_keys_start)).performClick()
                val words =
                    compose.onAllNodesWithTag(LOAD_KEYS_WORD_TAG).fetchSemanticsNodes().map { node ->
                        node.config[SemanticsProperties.Text].joinToString("")
                    }
                compose.onNodeWithText(app.getString(R.string.load_keys_next)).performClick()
                val wrong = words.dropLast(1) + list.words.first { it !in words }
                next = SealedSetup.seal("sleevesight-keys:v1\nlastfm=$LAST_FM_KEY", wrong, JvmKeyCrypto)
                val opening = app.getString(R.string.load_keys_opening)
                idleUntil("the opening step") {
                    compose.onAllNodes(hasText(opening)).fetchSemanticsNodes().isNotEmpty()
                }
                // The right code, read while the wrong one opens.
                next = SealedSetup.seal("sleevesight-keys:v1\nlastfm=$LAST_FM_KEY", words, JvmKeyCrypto)
                idleUntil("the right code read") {
                    compose.waitForIdle()
                    next == null
                }
                first.countDown()
                val read = app.getString(R.string.load_keys_read_title)
                idleUntil("the right code opened") {
                    compose.onAllNodes(hasText(read)).fetchSemanticsNodes().isNotEmpty()
                }
            } finally {
                first.countDown()
            }
        }
    }

    @Test
    fun wordsRenewedWhileACodeOpensLeaveItRefusedAsExpired() {
        // The phone's clock, moved by the test: the words are drawn at 0.
        var now = 0L
        var next by mutableStateOf<String?>(null)
        val reading: QrScanner = { onScanned ->
            val read = next
            LaunchedEffect(read) {
                if (read != null) {
                    next = null
                    onScanned(read)
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    activity.setContent {
                        LoadKeysScreen(
                            emptyMap(),
                            Settings(),
                            reading,
                            { _, _ -> Completed(emptyList(), null) },
                            { null },
                            {},
                            {},
                            crypto = holdingFirst,
                            clock = { now },
                        )
                    }
                }
                compose.onNodeWithText(app.getString(R.string.load_keys_start)).performClick()
                val words =
                    compose.onAllNodesWithTag(LOAD_KEYS_WORD_TAG).fetchSemanticsNodes().map { node ->
                        node.config[SemanticsProperties.Text].joinToString("")
                    }
                compose.onNodeWithText(app.getString(R.string.load_keys_next)).performClick()
                next = SealedSetup.seal("sleevesight-keys:v1\nlastfm=$LAST_FM_KEY", words, JvmKeyCrypto)
                val opening = app.getString(R.string.load_keys_opening)
                idleUntil("the opening step") {
                    compose.onAllNodes(hasText(opening)).fetchSemanticsNodes().isNotEmpty()
                }
                // Their time runs out while the code opens: new words are drawn.
                now = WORDS_LIFETIME.inWholeMilliseconds
                compose.mainClock.advanceTimeBy(2_000)
                first.countDown()
                val expired = app.getString(R.string.load_keys_expired_title)
                idleUntil("the code refused") {
                    compose.onAllNodes(hasText(expired)).fetchSemanticsNodes().isNotEmpty()
                }
                val read = app.getString(R.string.load_keys_read_title)
                assertTrue(compose.onAllNodes(hasText(read)).fetchSemanticsNodes().isEmpty())
            } finally {
                first.countDown()
            }
        }
    }

    private companion object {
        const val LAST_FM_KEY = "0123456789abcdef0123456789abcdef"

        const val TAG_BYTES = 16
    }
}
