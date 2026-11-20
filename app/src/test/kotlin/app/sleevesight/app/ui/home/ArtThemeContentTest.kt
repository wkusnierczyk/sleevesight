package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is under the art's colours ([ArtTheme]) is kept as it is when they come, off the main thread
 * (#1469): before, it was composed anew then, its state reset and a tap in progress dropped, as a tap
 * on the cover to turn it over (`VideoSourceHomeScreenTest`).
 */
@RunWith(RobolectricTestRunner::class)
class ArtThemeContentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theContentIsKeptWhenTheArtsColoursCome() {
        val art = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var app: Int? = null
        var primary: Int? = null
        var made = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        app = MaterialTheme.colorScheme.primary.hashCode()
                        ArtTheme(art) {
                            remember { made++ }
                            primary = MaterialTheme.colorScheme.primary.hashCode()
                        }
                    }
                }
            }
            // The art's colours, worked out off the main thread, in place of the app's.
            compose.waitUntil(WAIT_MS) { primary != null && primary != app }
            compose.waitForIdle()
            assertEquals(1, made)
        }
    }
}
