package app.sleevesight.app.ui.logo

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The turning record beside an in-progress message (#785), and its row ([Busy]): a screen reader
 * finds no node for the record and reads the message as it is; the record turns clockwise, one
 * turn in 1.8 s, and stands still and upright under *Remove animations*; 20 sp across, so a larger
 * font gives a larger record; and by a message that wraps, it sits by the first line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpinningRecordTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** [content] as an activity's content, in the app's light theme, at animator duration [scale]. */
    private fun show(
        scale: Float = 1f,
        content: @Composable () -> Unit,
        check: () -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity {
            it.setContent {
                SleevesightTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalAnimatorDurationScale provides scale) { content() }
                }
            }
        }
        // With the clock held (a record turning), one frame lays the screen out and draws it.
        if (compose.mainClock.autoAdvance) compose.waitForIdle() else compose.mainClock.advanceTimeByFrame()
        check()
    }

    private fun record() = compose.onNodeWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true)

    @Test
    fun aScreenReaderFindsNoNodeForTheRecordAndReadsTheMessageAsItIs() =
        show(content = {
            Box(Modifier.testTag("row").semantics(mergeDescendants = true) {}) {
                Busy("Looking…", MaterialTheme.typography.bodyMedium)
            }
        }) {
            // Unmerged: the record's node holds its test tag and nothing a screen reader reads or acts on.
            val config = record().fetchSemanticsNode().config
            assertEquals(listOf(SemanticsProperties.TestTag.name), config.map { it.key.name })
            assertEquals(1, compose.onAllNodesWithText("Looking…", useUnmergedTree = true).fetchSemanticsNodes().size)
            // Merged: what a screen reader reads of the row is the message alone.
            val merged = compose.onNodeWithTag("row").fetchSemanticsNode().config
            assertEquals(listOf("Looking…"), merged[SemanticsProperties.Text].map { it.text })
            assertEquals(null, merged.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(null, merged.getOrNull(SemanticsProperties.Role))
        }

    @Test
    fun aMessageAloneIsReadAsItIsWithTheRecordBeforeIt() =
        show(content = { Busy("Loading…", MaterialTheme.typography.bodyMedium) }) {
            val message = compose.onNodeWithText("Loading…").fetchSemanticsNode()
            assertEquals(listOf("Loading…"), message.config[SemanticsProperties.Text].map { it.text })
            val disc = record().fetchSemanticsNode().boundsInRoot
            // The record, 8 dp, then the message, centred on its one line.
            assertEquals(8f * compose.density.density, message.boundsInRoot.left - disc.right, 1f)
            assertEquals(message.boundsInRoot.center.y, disc.center.y, 1f)
        }

    // The record as drawn, light theme: whether its face's short line is at the bottom (upright)
    // or at the left (a quarter turn clockwise), from where the white is.
    private enum class Facing { UPRIGHT, QUARTER, OTHER }

    private fun facing(): Facing {
        val image = record().captureToImage().asAndroidBitmap()
        keep(image)
        val unit = image.width / 16f
        val centre = image.width / 2f
        // The face's short line, 3.9 units below the centre, upright.
        val bottom = white(image, centre, centre + 3.9f * unit)
        val left = white(image, centre - 3.9f * unit, centre)
        return when {
            bottom && !left -> Facing.UPRIGHT
            left && !bottom -> Facing.QUARTER
            else -> Facing.OTHER
        }
    }

    private fun white(
        image: Bitmap,
        x: Float,
        y: Float,
    ): Boolean {
        val white = Color.White.toArgb()
        val near = { c: Int -> listOf(16, 8, 0).all { abs((c shr it and 0xFF) - (white shr it and 0xFF)) <= 24 } }
        return near(image.getPixel(x.roundToInt(), y.roundToInt()))
    }

    private var kept = 0

    private fun keep(image: Bitmap) {
        val dir = System.getenv("SLEEVESIGHT_PICTURES")?.let { java.io.File(it).apply { mkdirs() } } ?: return
        java.io
            .File(
                dir,
                "record-${kept++}.png",
            ).outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theRecordTurnsClockwiseAQuarterIn450Ms() {
        compose.mainClock.autoAdvance = false
        show(scale = 1f, content = { SpinningRecord() }) {
            assertEquals(Facing.UPRIGHT, facing())
            compose.mainClock.advanceTimeBy(TURN_QUARTER_MS)
            assertEquals(Facing.QUARTER, facing())
            compose.mainClock.advanceTimeBy(3 * TURN_QUARTER_MS)
            assertEquals(Facing.UPRIGHT, facing())
        }
    }

    @Test
    fun underRemoveAnimationsTheRecordStandsStillUpright() {
        compose.mainClock.autoAdvance = false
        show(scale = 0f, content = { SpinningRecord() }) {
            assertEquals(Facing.UPRIGHT, facing())
            for (step in 1..5) {
                compose.mainClock.advanceTimeBy(TURN_QUARTER_MS + step * 37L)
                assertEquals(Facing.UPRIGHT, facing())
            }
        }
    }

    @Test
    fun theRecordIs20DpAcrossAtTheDefaultFontSize() =
        show(content = { SpinningRecord() }) {
            val bounds = record().fetchSemanticsNode().boundsInRoot
            assertEquals(20f * compose.density.density, bounds.width, 1f)
            assertEquals(bounds.width, bounds.height, .5f)
        }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheRecordGrowsAsTextDoes() {
        var expected = 0f
        show(content = {
            expected = with(LocalDensity.current) { 20.sp.toPx() }
            SpinningRecord()
        }) {
            val bounds = record().fetchSemanticsNode().boundsInRoot
            assertEquals(expected, bounds.width, 1f)
            assertTrue("larger than at 1 (${bounds.width} px)", bounds.width > 20f * compose.density.density * 1.4f)
        }
    }

    @Test
    fun byAMessageThatWrapsTheRecordSitsByTheFirstLine() {
        val text = "Waiting for the computer: on the page, choose Connect and pick this phone."
        var line = 0f
        show(content = {
            val style = MaterialTheme.typography.bodyLarge
            line = with(LocalDensity.current) { style.lineHeight.toPx() }
            Column(Modifier.width(160.dp)) { Busy(text, style) }
        }) {
            val message = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            val disc = record().fetchSemanticsNode().boundsInRoot
            assertTrue("wraps", message.height > 2 * line)
            // The record's centre on the first line's, not on the message's.
            assertEquals(message.top + line / 2, disc.center.y, 1f)
            assertEquals(
                1,
                compose.onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun besideASmallMessageTheRowIsAsTallAsTheMessageAndTheRecordOverhangsIt() {
        show(content = {
            val style = MaterialTheme.typography.bodySmall
            Column {
                Box(Modifier.testTag("with")) { Busy("Looking…", style, textModifier = Modifier.testTag("message")) }
                Box(Modifier.testTag("without")) { Busy("Found the track.", style, record = false) }
            }
        }) {
            val withRecord = compose.onNodeWithTag("with").fetchSemanticsNode().boundsInRoot
            val withoutRecord = compose.onNodeWithTag("without").fetchSemanticsNode().boundsInRoot
            val message = compose.onNodeWithTag("message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val disc = record().fetchSemanticsNode().boundsInRoot
            // A row keeps its height when its record goes (#785): the message's, not the record's.
            assertEquals(withoutRecord.height, withRecord.height, 1f)
            assertEquals(message.height, withRecord.height, 1f)
            // The record, taller than the 16 sp line, on its centre, over the row's top and bottom alike.
            assertTrue("taller than the line", disc.height > message.height + 2f)
            assertEquals(message.center.y, disc.center.y, 1f)
            assertEquals(withRecord.top - disc.top, disc.bottom - withRecord.bottom, 1f)
        }
    }

    @Test
    fun theMessageKeepsItsNodeAsTheRecordGoes() {
        var busy by mutableStateOf(true)
        show(content = {
            Busy(
                if (busy) "Exporting…" else "Exported.",
                MaterialTheme.typography.bodyMedium,
                textModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                record = busy,
            )
        }) {
            val before = compose.onNodeWithText("Exporting…").fetchSemanticsNode()
            assertEquals(1, records())
            busy = false
            compose.waitForIdle()
            val after = compose.onNodeWithText("Exported.").fetchSemanticsNode()
            assertEquals(before.id, after.id)
            assertEquals(LiveRegionMode.Polite, after.config[SemanticsProperties.LiveRegion])
            assertEquals(0, records())
        }
    }

    @Test
    fun theRootFollowsThePhonesSettingWithOneObserverGoneWithIt() {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var shown by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var resolver: ContentResolver
            scenario.onActivity {
                resolver = it.contentResolver
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        // As `MainActivity` provides it, with nothing of a test's.
                        if (shown) {
                            CompositionLocalProvider(
                                LocalAnimatorDurationScale provides followedAnimatorDurationScale(),
                            ) { Box { SpinningRecord() } }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            // Under *Remove animations*, still; one observer, the root's, for the records under it.
            assertEquals(1, shadowOf(resolver).getContentObservers(uri).size)
            assertEquals(Facing.UPRIGHT, facing())
            compose.mainClock.advanceTimeBy(TURN_QUARTER_MS)
            assertEquals(Facing.UPRIGHT, facing())

            // Animations on again while it shows: it turns.
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            resolver.notifyChange(uri, null)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(Facing.UPRIGHT, facing())
            compose.mainClock.advanceTimeBy(TURN_QUARTER_MS)
            assertEquals(Facing.QUARTER, facing())

            // Gone with the root: the observer too.
            shown = false
            compose.mainClock.advanceTimeByFrame()
            assertEquals(0, records())
            assertEquals(emptyList<Any>(), shadowOf(resolver).getContentObservers(uri).toList())
        }
    }

    private fun records() =
        compose.onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        const val TURN_QUARTER_MS = 450L
    }
}
