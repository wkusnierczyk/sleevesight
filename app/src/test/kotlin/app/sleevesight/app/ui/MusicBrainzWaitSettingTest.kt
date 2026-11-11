package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.MUSICBRAINZ_WAIT_SLIDER_TAG
import app.sleevesight.app.ui.settings.MUSICBRAINZ_WAIT_VALUE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * *Wait when busy* (#1120): under *Sources*, below MusicBrainz, a stepped slider with the stops
 * *Don’t wait*, *15 s*, *30 s* and *1 min*, each labelled, 15 s chosen by default and shown at the
 * row's right; a move stored at once; read by a screen reader by its name with the value in words;
 * dimmed and not to be moved while MusicBrainz is off.
 */
@RunWith(RobolectricTestRunner::class)
class MusicBrainzWaitSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)

    // On the main thread, so one at a time and in order: two saves in quick succession land as made;
    // and with the screen, so a wait for a save has no other thread to wait on (#1183).
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showSources(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.SOURCES)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_musicbrainz_wait)) }
            block()
        }
    }

    private fun slider() = compose.onNodeWithTag(MUSICBRAINZ_WAIT_SLIDER_TAG).performScrollTo()

    /** What a screen reader says of the slider's value. */
    private fun spoken(): String? = slider().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun shownValue(): String? =
        compose
            .onNodeWithTag(MUSICBRAINZ_WAIT_VALUE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }

    /** Moves the slider to stop [index], as a screen reader's adjustment does. */
    private fun moveTo(index: Int) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Wait when busy", text(R.string.settings_musicbrainz_wait))
        assertEquals(
            "When MusicBrainz is busy, Sleevesight asks again, waiting longer each time, for up to this long " +
                "before going on without it. A longer wait gives fuller genres and dates; a shorter one shows " +
                "the insight sooner.",
            text(R.string.settings_musicbrainz_wait_summary),
        )
        assertEquals("Don’t wait", text(R.string.settings_musicbrainz_wait_none))
        assertEquals("15 s", app.getString(R.string.settings_musicbrainz_wait_seconds, 15))
        assertEquals("1 min", app.getString(R.string.settings_musicbrainz_wait_minutes, 1))
        assertEquals(listOf(Duration.ZERO, 15.seconds, 30.seconds, 1.minutes), Settings.MUSICBRAINZ_WAITS)
        assertEquals(15.seconds, Settings().musicBrainzWait)
    }

    @Test
    fun theRowComesBelowMusicBrainzWithFifteenSecondsChosenAndEveryStopLabelled() =
        showSources {
            val row =
                compose
                    .onNodeWithText(
                        text(R.string.settings_musicbrainz_wait),
                    ).performScrollTo()
                    .fetchSemanticsNode()
            val musicBrainz = compose.onNodeWithText(text(R.string.settings_source_musicbrainz)).fetchSemanticsNode()
            val wikipedia = compose.onNodeWithText(text(R.string.settings_source_wikipedia)).fetchSemanticsNode()
            assertTrue(musicBrainz.positionInRoot.y < row.positionInRoot.y)
            assertTrue(row.positionInRoot.y < wikipedia.positionInRoot.y)
            assertEquals("15 s", shownValue())
            for (label in listOf("Don’t wait", "30 s", "1 min")) {
                assertTrue(
                    label,
                    compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                )
            }
            val node = slider().fetchSemanticsNode()
            assertEquals(listOf("Wait when busy"), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals("15 seconds", spoken())
            slider().assertIsEnabled()
        }

    @Test
    fun aMoveIsStoredAtOnceAndTheValueFollows() =
        showSources {
            moveTo(0)
            compose.waitUntil(WAIT_MS) { settings.now().musicBrainzWait == Duration.ZERO }
            compose.waitUntil(WAIT_MS) { spoken() == "Don’t wait" }
            assertEquals("Don’t wait", shownValue())
            moveTo(3)
            compose.waitUntil(WAIT_MS) { settings.now().musicBrainzWait == 1.minutes }
            compose.waitUntil(WAIT_MS) { spoken() == "1 minute" }
            assertEquals("1 min", shownValue())
            moveTo(2)
            compose.waitUntil(WAIT_MS) { settings.now().musicBrainzWait == 30.seconds }
            compose.waitUntil(WAIT_MS) { spoken() == "30 seconds" }
        }

    // A drag to 30 s and back to 15 s in one gesture, faster than the store answers: the move back is
    // written too, and the setting ends where the drag did.
    @Test
    fun aQuickDragAwayAndBackEndsWhereItEnded() =
        showSources {
            slider().performTouchInput {
                val inset = 12.dp.toPx()
                val stop = { i: Int -> Offset(inset + (width - 2 * inset) * i / 3, centerY) }
                down(stop(1))
                moveTo(stop(2))
                moveTo(stop(1))
                up()
            }
            compose.waitUntil(WAIT_MS) { spoken() == "15 seconds" }
            compose.waitForIdle()
            assertEquals(15.seconds, settings.now().musicBrainzWait)
            assertEquals("15 seconds", spoken())
        }

    @Test
    fun withMusicBrainzOffTheSliderCannotBeMoved() {
        runBlocking { settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.MUSICBRAINZ) } }
        showSources {
            slider().assertIsNotEnabled()
            assertEquals("15 seconds", spoken())
        }
    }
}
