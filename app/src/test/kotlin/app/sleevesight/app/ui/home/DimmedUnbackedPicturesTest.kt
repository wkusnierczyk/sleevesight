package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The mark for text the sources do not back (#954), drawn as on a phone, light and dark, at the
 * default font size and twice it: dimmed sentences upright at 60 %, a `?` chip after the last of each
 * run of them, none after the AI model's own sentence, and the note under the insight starting with
 * the same chip. With `SLEEVESIGHT_PICTURES` set, each is saved there as a picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DimmedUnbackedPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun picture(
        name: String,
        dark: Boolean,
    ) {
        val insight =
            HomeFixtures.written(
                "Sundown was recorded in 1973 [MB1]. It is *Sundown* at its most relaxed. It made him a star. " +
                    "It topped the charts [W1]. It was a hit in Japan [MB9]. It is loved by guitarists [M].",
                allowModel = true,
            )
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = true,
                offers = InsightOffers(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp)) {
                                InsightCard(insight, context, InsightActions({}, {}, {}, onReport = { _, _ -> }))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(UNBACKED_NOTE_TAG).assertExists()
            val dir = out ?: return
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) picture("dimmed-${if (dark) "dark" else "light"}-$size", dark)
        }
    }
}
