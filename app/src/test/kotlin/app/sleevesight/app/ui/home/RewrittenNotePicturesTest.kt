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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.RewriteOffer
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.WrittenBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The insight's footer with what a rewrite changed (#854), drawn as on a phone, at the default font
 * size and twice it: the play an insight was rewritten, a later play with *Rewrite insight* offered
 * again, and the offer after *Look again* found something new, each with a dimmed sentence and its
 * note. Each first line is checked; with `SLEEVESIGHT_PICTURES` set, each is also saved there as a
 * picture, as `InsightBylineScreenTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RewrittenNotePicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun picture(
        name: String,
        line: String,
        offers: InsightOffers,
        rewritten: Set<RewritePart>,
        cached: Boolean,
    ) {
        val insight =
            HomeFixtures
                .written("Sundown was recorded in 1973 [MB1]. It is a classic.", cached = cached)
                .copy(writtenBy = HAIKU_HIGH, rewritten = rewritten)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = offers,
                cardShown = offers.rewrite,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp)) {
                                InsightCard(insight, context, InsightActions({}, {}, {}, onReport = { _, _ -> }))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(line).assertExists()
            compose.onNodeWithText(NOTE).assertExists()
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
            picture(
                "rewritten-just-now-light-$size",
                "Rewritten with Claude Haiku 5.5 high effort.",
                InsightOffers(),
                setOf(RewritePart.MODEL),
                cached = false,
            )
            picture(
                "rewritten-later-play-offered-again-light-$size",
                "Rewritten with new findings from sources and Claude Haiku 5.5 high effort. " +
                    "Tap Rewrite insight to have AI rewrite the insight with GPT-6.1 Sol high effort.",
                InsightOffers(rewrite = true, rewriteWith = RewriteOffer(setOf(RewritePart.MODEL), SOL_HIGH)),
                setOf(RewritePart.NEW_FINDINGS, RewritePart.MODEL),
                cached = true,
            )
            picture(
                "offer-after-look-again-light-$size",
                "As written previously. Tap Rewrite insight to have AI rewrite the insight with new findings " +
                    "from sources.",
                InsightOffers(
                    rewrite = true,
                    somethingNew = true,
                    rewriteWith = RewriteOffer(setOf(RewritePart.NEW_FINDINGS), HAIKU_HIGH),
                ),
                emptySet(),
                cached = true,
            )
        }
    }

    private companion object {
        const val NOTE = "Dimmed text is not backed by the sources."
        val HAIKU_HIGH = WrittenBy(5, ProviderId.ANTHROPIC, "claude-haiku-5-5", ReasoningEffort.HIGH)
        val SOL_HIGH = WrittenBy(5, ProviderId.OPENAI, "gpt-6.1-sol", ReasoningEffort.HIGH)
    }
}
