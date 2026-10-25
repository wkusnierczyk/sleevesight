package app.sleevesight.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.ai.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The names the model lists gave, kept by id for the settings (#444). */
@RunWith(RobolectricTestRunner::class)
class ListedModelNamesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val kept = app.getSharedPreferences("model_names", Context.MODE_PRIVATE)

    @Test
    fun aListedNameIsKeptButNotAnIdOrANameFromTheAppsOwnTable() {
        val names = ListedModelNames(app)
        names.learn(
            listOf(
                ModelInfo("claude-opus-5-5", "Claude Opus 5.5", null),
                // OpenAI's, named from `ModelNames`: kept there, so a revised table reaches it.
                ModelInfo("gpt-6-luna", "GPT-6 Luna", null),
                ModelInfo("gpt-other", "gpt-other", null),
            ),
        )
        assertTrue(kept.contains("claude-opus-5-5"))
        assertFalse(kept.contains("gpt-6-luna"))
        assertFalse(kept.contains("gpt-other"))

        val again = ListedModelNames(app)
        assertEquals("Claude Opus 5.5", again.of("claude-opus-5-5"))
        assertEquals("GPT-6 Luna", again.of("gpt-6-luna"))
        assertEquals("gpt-other", again.of("gpt-other"))
    }
}
