package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.settings.FoldedSection
import app.sleevesight.app.ui.settings.LocalSettingsNavigator
import app.sleevesight.app.ui.settings.ProvideSettingsNavigator
import app.sleevesight.app.ui.settings.SectionFolds
import app.sleevesight.app.ui.settings.SettingsNavigator
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.settings.sentenceWith
import app.sleevesight.app.ui.theme.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The links between settings (#539): a link to a section unfolds it and lands on its heading, and
 * [sentenceWith] sets a link in its sentence. The key links are `SettingsKeyLinksTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun aLinkToASectionUnfoldsItAndLandsOnItsHeading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var navigator: SettingsNavigator? = null
            scenario.onActivity { activity ->
                activity.setContent {
                    val folds = remember { SectionFolds(setOf(SettingsSection.PLAYERS, SettingsSection.SOURCES)) }
                    ProvideSettingsNavigator(folds) {
                        navigator = LocalSettingsNavigator.current
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            // Sections taller than the screen once unfolded, so the last is out of view.
                            for (section in listOf(SettingsSection.PLAYERS, SettingsSection.SOURCES)) {
                                FoldedSection(section, folds) { Spacer(Modifier.height(2_000.dp)) }
                            }
                            FoldedSection(SettingsSection.ABOUT, folds) { Text(ABOUT_ROWS) }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnUiThread { navigator!!.go(SettingsTarget(SettingsSection.ABOUT)) }
            val heading = hasTestTag(SettingsSection.ABOUT.tag) and isFocused()
            compose.waitUntil(conditionDescription = "the About heading focused", timeoutMillis = 5_000) {
                compose.onAllNodes(heading).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(SettingsSection.ABOUT.tag).assertIsDisplayed()
            // Unfolded: its rows are drawn, under the heading.
            compose.onNodeWithText(ABOUT_ROWS).assertExists()
        }
    }

    @Test
    fun aLandingOnARowNoSpotIsDrawnForIsDropped() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var navigator: SettingsNavigator? = null
            scenario.onActivity { activity ->
                activity.setContent {
                    val folds = remember { SectionFolds() }
                    ProvideSettingsNavigator(folds) {
                        navigator = LocalSettingsNavigator.current
                        Column { FoldedSection(SettingsSection.ABOUT, folds) { Text(ABOUT_ROWS) } }
                    }
                }
            }
            compose.waitForIdle()
            // A row no longer shown, as a route or a saved state from an earlier version could name.
            compose.runOnUiThread { navigator!!.go(SettingsTarget(SettingsSection.ABOUT, "gone")) }
            // A frame for the section to unfold and its spots to claim their targets, another to drop it.
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNull(navigator!!.landing)
            assertFalse(navigator!!.scrolling)
            // Its section is unfolded all the same.
            compose.onNodeWithText(ABOUT_ROWS).assertExists()
        }
    }

    @Test
    fun onlyALandingStillScrollingIsSaved() {
        val folds = SectionFolds()
        val navigator = SettingsNavigator(folds)
        val saver = SettingsNavigator.saver(folds)
        val scope = SaverScope { true }
        val target = SettingsTarget.key(SecretId.EXA)
        assertNull(with(saver) { scope.save(navigator) })
        navigator.go(target)
        assertEquals(arrayListOf("SECRETS", "key_EXA"), with(saver) { scope.save(navigator) })
        navigator.scrolled(navigator.landing!!)
        // Scrolled: the row is being focused, or flashing; nothing to land on again.
        assertNull(with(saver) { scope.save(navigator) })
    }

    @Test
    fun aSavedLandingWhoseSectionIsFoldedIsDropped() {
        val saved = arrayListOf("SECRETS", "key_EXA")
        val folded = SettingsNavigator.saver(SectionFolds()).restore(saved)!!
        assertNull(folded.landing)
        assertFalse(folded.scrolling)
        val unfolded = SettingsNavigator.saver(SectionFolds(setOf(SettingsSection.SECRETS))).restore(saved)!!
        assertEquals(SettingsTarget.key(SecretId.EXA), unfolded.landing?.target)
        assertTrue(unfolded.scrolling)
    }

    @Test
    fun aLinkIsSetInItsSentence() {
        val link = TextPart.Action("Exa API key", "tag") {}
        assertEquals(
            listOf("Set your ", "Exa API key", " here."),
            sentenceWith("Set your Exa API key here.", link).map { it.text },
        )
        assertEquals(link, sentenceWith("Set your Exa API key here.", link)[1])
        // No empty words around a link at either end.
        assertEquals(
            listOf("Exa API key", ": set it here."),
            sentenceWith("Exa API key: set it here.", link).map { it.text },
        )
        // A sentence without the link's text is plain words.
        assertEquals(listOf(TextPart.Words("Set your key here.")), sentenceWith("Set your key here.", link))
    }

    @Test
    fun everyTranslationOfTheKeySentenceHasTheKeysNameForItsLink() {
        // Without the name, `sentenceWith` finds no place for the link, and the sentence shows without it.
        val sentences =
            File("src/main/res")
                .listFiles { dir -> dir.name.startsWith("values") }
                .orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
                .flatMap { file ->
                    val place = "${file.parentFile.name}/${file.name}"
                    KEY_SENTENCE.findAll(file.readText()).map { "$place: ${it.groupValues[1]}" }
                }
        assertTrue("found in values", sentences.any { it.startsWith("values/") })
        assertEquals(emptyList<String>(), sentences.filter { "%1\$s" !in it })
    }

    private companion object {
        val KEY_SENTENCE = Regex("""<string name="settings_set_key">(.*?)</string>""")

        const val ABOUT_ROWS = "About's rows"
    }
}
