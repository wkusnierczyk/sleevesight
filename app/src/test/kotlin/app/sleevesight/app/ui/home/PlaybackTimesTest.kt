package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The playback position's times (#1097): whether the length sits beside the position or on a line under
 * it is fixed for the track, at any width, so nothing under the cover moves as the track plays
 * (0:00, 9:59, 10:00, the length). Real text measurement, as on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class PlaybackTimesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private var width by mutableStateOf(MIN_WIDTH.dp)
    private var position by mutableStateOf(Duration.ZERO)

    @Test
    fun theTimesTakeAsManyLinesAtTheEndOfTheTrackAsAtItsStart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Box(Modifier.width(width)) {
                            ArtAndProgress(
                                null,
                                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PAUSED, position = position),
                                LENGTH,
                            )
                        }
                    }
                }
            }
            for (dp in MIN_WIDTH..MAX_WIDTH step STEP) {
                width = dp.dp
                val heights =
                    listOf(Duration.ZERO, 9.minutes + 59.seconds, 10.minutes, LENGTH).map { at ->
                        position = at
                        compose.waitForIdle()
                        compose
                            .onNodeWithContentDescription(app.getString(R.string.home_progress))
                            .fetchSemanticsNode()
                            .size.height
                    }
                assertEquals("at $dp dp: $heights", 1, heights.distinct().size)
            }
        }
    }

    private companion object {
        val LENGTH = 11.minutes + 11.seconds
        const val MIN_WIDTH = 40
        const val MAX_WIDTH = 160
        const val STEP = 2
    }
}
