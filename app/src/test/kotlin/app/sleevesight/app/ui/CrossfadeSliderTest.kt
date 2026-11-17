package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.CROSSFADE_SLIDER_TAG
import app.sleevesight.app.ui.settings.CROSSFADE_STOPS_TAG
import app.sleevesight.app.ui.settings.CROSSFADE_VALUE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * *Insights* → *Crossfade* as a stepped slider (#1365, as #1126 has a choice from a range):
 * *Autodetect*, then 0 to 15 seconds, seventeen stops, labelled only at the ends; the value at the
 * title's right; read by a screen reader by name and value in words; a move stored at once; dimmed
 * and still while *Show insights* is off; a stored value between the stops (a fraction of a
 * second) shown and read as stored, not rewritten.
 */
@RunWith(RobolectricTestRunner::class)
class CrossfadeSliderTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun showInsights(
        change: (Settings) -> Settings = { it },
        block: () -> Unit,
    ) {
        runBlocking { settings.update { change(it.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(CROSSFADE_SLIDER_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    private fun slider(): SemanticsNodeInteraction = compose.onNodeWithTag(CROSSFADE_SLIDER_TAG).performScrollTo()

    /** What a screen reader says of the slider: its name, then its value. */
    private fun read(): Pair<String?, String?> {
        val config = slider().fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription)?.single() to
            config.getOrNull(SemanticsProperties.StateDescription)
    }

    private fun texts(tag: String): List<String> {
        val out = mutableListOf<String>()

        fun collect(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotEmpty()) out += it.text }
            n.children.forEach(::collect)
        }
        collect(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private fun moveTo(index: Int) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
        compose.waitForIdle()
    }

    @Test
    fun crossfadeIsASliderLabelledAtItsEndsAndReadByNameAndValue() =
        showInsights {
            assertEquals(listOf("Autodetect", "15 s"), texts(CROSSFADE_STOPS_TAG))
            assertEquals(listOf("Autodetect"), texts(CROSSFADE_VALUE_TAG))
            assertEquals("Crossfade" to "Autodetect", read())
        }

    @Test
    fun aMoveIsStoredAtOnceAndReadInWords() =
        showInsights {
            moveTo(6)
            compose.waitUntil(WAIT_MS) { settings.now().crossfade == Crossfade.Fixed(5.seconds) }
            assertEquals(listOf("5 s"), texts(CROSSFADE_VALUE_TAG))
            assertEquals("Crossfade" to "5 seconds", read())
            moveTo(2)
            compose.waitUntil(WAIT_MS) { settings.now().crossfade == Crossfade.Fixed(1.seconds) }
            assertEquals("Crossfade" to "1 second", read())
            moveTo(0)
            compose.waitUntil(WAIT_MS) { settings.now().crossfade == Crossfade.Autodetect }
        }

    @Test
    fun aStoredCrossfadeShowsAtItsStop() =
        showInsights({ it.copy(crossfade = Crossfade.Fixed(15.seconds)) }) {
            assertEquals(listOf("15 s"), texts(CROSSFADE_VALUE_TAG))
            assertEquals("Crossfade" to "15 seconds", read())
        }

    @Test
    fun aStoredCrossfadeBetweenTheStopsIsShownAsStoredAndNotRewritten() =
        showInsights({ it.copy(crossfade = Crossfade.Fixed(7600.milliseconds)) }) {
            // The thumb at the nearest stop, the value shown and read as stored.
            assertEquals(listOf("7.6 s"), texts(CROSSFADE_VALUE_TAG))
            assertEquals("Crossfade" to "7.6 seconds", read())
            compose.waitForIdle()
            assertEquals(Crossfade.Fixed(7600.milliseconds), settings.now().crossfade)
        }

    @Test
    fun whileShowInsightsIsOffTheSliderCannotBeMoved() =
        showInsights({ it.copy(writeInsights = false) }) {
            slider().assertIsNotEnabled()
        }
}
