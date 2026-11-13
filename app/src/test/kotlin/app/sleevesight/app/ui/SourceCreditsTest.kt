package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The credits name every source the app asks, each with what the app takes from it (#1241). */
@RunWith(RobolectricTestRunner::class)
class SourceCreditsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    @Test
    fun theCreditsListEverySource() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen(onClose = {}) } }
            val sources =
                listOf(
                    R.string.credits_musicbrainz to R.string.credits_musicbrainz_text,
                    R.string.credits_musicbrainz_tags to R.string.credits_musicbrainz_tags_text,
                    R.string.credits_wikipedia to R.string.credits_wikipedia_text,
                    R.string.credits_wikidata to R.string.credits_wikidata_text,
                    R.string.credits_lastfm to R.string.credits_lastfm_text,
                    // Asked whether a track has lyrics, for its genre Instrumental (#997).
                    R.string.credits_lrclib to R.string.credits_lrclib_text,
                    R.string.credits_exa to R.string.credits_exa_text,
                )
            for ((name, text) in sources) {
                compose.onNodeWithText(app.getString(name)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(app.getString(text)).performScrollTo().assertIsDisplayed()
            }
        }
    }
}
