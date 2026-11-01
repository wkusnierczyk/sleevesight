package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import app.sleevesight.app.ui.settings.LoadKeysScreen
import app.sleevesight.core.keys.KeyCrypto
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.platform.JvmKeyCrypto
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

/**
 * *Load keys* while it opens a sealed code (#785), as picked in the mock-up: the heading *Opening
 * the code…* with the turning record before it on its line, no progress bar, and the heading still
 * a heading, announced as it shows.
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
                        LoadKeysScreen(emptyMap(), scanner, { emptyList() }, {}, crypto = crypto)
                    }
                }
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
            } finally {
                held.countDown()
            }
        }
    }

    private companion object {
        const val TAG_BYTES = 16
    }
}
