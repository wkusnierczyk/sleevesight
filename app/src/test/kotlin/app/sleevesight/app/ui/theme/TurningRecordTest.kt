package app.sleevesight.app.ui.theme

import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The small turning record (#773; #785 and #786 reuse it): hidden from a screen reader, which reads
 * the text beside it; turning, but still under the system's *Remove animations*; about 20 dp at the
 * default font size, growing with it.
 */
@RunWith(RobolectricTestRunner::class)
class TurningRecordTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Row {
                        TurningRecord(Modifier.testTag(TAG))
                        Text("Searching with AI…")
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun record() = compose.onNodeWithTag(TAG, useUnmergedTree = true).fetchSemanticsNode()

    @Test
    fun itIsHiddenFromAScreenReaderAndTheTextBesideItIsRead() =
        show {
            val record = record()
            assertNull(record.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull(record.config.getOrNull(SemanticsProperties.Text))
            assertNull(record.config.getOrNull(SemanticsProperties.Role))
            assertTrue(record.children.isEmpty())
            compose.onNodeWithText("Searching with AI…").assertExists()
        }

    @Test
    fun itTurnsWithTheAnimationsOn() =
        show {
            assertTrue(record().config[RecordTurning])
        }

    @Test
    fun itIsStillUnderRemoveAnimations() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(animationsRemoved(app))
        show { assertFalse(record().config[RecordTurning]) }
    }

    @Test
    fun itIsAbout20DpAtTheDefaultFontSize() =
        show {
            val size = record().size
            val density = app.resources.displayMetrics.density
            assertEquals(20f, size.width / density, 0.5f)
            assertEquals(size.width, size.height)
        }

    @Test
    @Config(fontScale = 2f)
    fun itGrowsWithTheFontAsTextDoes() =
        show {
            // Android scales large text by less than the scale (non-linear font scaling): so does the record.
            val grown = record().size.width / app.resources.displayMetrics.density
            val expected =
                with(
                    compose
                        .onNodeWithTag(TAG, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .layoutInfo.density,
                ) { 20.sp.toDp().value }
            assertEquals(expected, grown, 0.5f)
            assertTrue(grown > 30f)
        }

    private companion object {
        const val TAG = "record"
    }
}
