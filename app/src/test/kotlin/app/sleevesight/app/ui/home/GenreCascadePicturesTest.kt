package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.GenreListMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * *Why is this …?*'s new states (#695) in a genre's panel, drawn as on a phone, light and dark, at
 * the default font size and twice it: a reason wholly from the model's knowledge, with its line; one
 * from web pages, credited "with web search"; nothing explains it, with both off and with only the
 * web off. Each one's line is checked; with `SLEEVESIGHT_PICTURES` set, each is also saved there as
 * a picture, as `RewrittenNotePicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenreCascadePicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun picture(
        name: String,
        reason: GenreReason,
        line: String,
        dark: Boolean,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                GenresContent(
                                    CascadeFixtures.playing,
                                    Settings(),
                                    notes = PanelNotes(CascadeFixtures.Notes(reason)),
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag(CascadeFixtures.ROCK_CHIP).unfold()
            compose.waitForIdle()
            compose.onNodeWithText(line, useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            val dir = out ?: return
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun pictures() {
        val states =
            listOf(
                Triple("from-model", CascadeFixtures.fromModel, CascadeFixtures.LINE),
                Triple("from-web", CascadeFixtures.fromWeb, "by ${CascadeFixtures.MODEL}, with web search"),
                Triple(
                    "not-found-both-off",
                    CascadeFixtures.notFound(GenreListMode.KNOWLEDGE, GenreListMode.WEB),
                    "Enable AI service knowledge or AI web search.",
                ),
                Triple(
                    "not-found-web-off",
                    CascadeFixtures.notFound(GenreListMode.WEB),
                    "Enable AI web search.",
                ),
            )
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                for ((name, reason, line) in states) picture("$name-$theme-$size", reason, line, dark)
            }
        }
    }
}
