package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import app.sleevesight.app.ui.settings.KEEP_HISTORY_SLIDER_TAG
import app.sleevesight.app.ui.settings.KEEP_HISTORY_STOPS_TAG
import app.sleevesight.app.ui.settings.KEEP_HISTORY_VALUE_TAG
import app.sleevesight.app.ui.settings.SESSION_GAP_SLIDER_TAG
import app.sleevesight.app.ui.settings.SESSION_GAP_STOPS_TAG
import app.sleevesight.app.ui.settings.SESSION_GAP_VALUE_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SteppedTags
import app.sleevesight.app.ui.settings.lifetimeTags
import app.sleevesight.core.model.SourceId
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * *History*'s ranges as stepped sliders (#1126): *Keep history for* (7, 30, 90 days, 1 year,
 * *Forever*) and *New session idle trigger* (10, 20, 30 min, 1 h, 2 h), every stop labelled; each
 * source's findings (1 to 90 days), labelled only at the ends; the value at the title's right; read
 * by a screen reader by name and value in words. *Keep history for* is stored on release, a shorter
 * time asked first; a stored value that is not a stop shows at the nearest.
 */
@RunWith(RobolectricTestRunner::class)
class HistorySlidersTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showHistory(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.HISTORY)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_keep_history)) }
            block()
        }
    }

    private fun slider(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    /** What a screen reader says of the slider [tag]: its name, then its value. */
    private fun read(tag: String): Pair<String?, String?> {
        val config = slider(tag).fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription)?.single() to
            config.getOrNull(SemanticsProperties.StateDescription)
    }

    private fun texts(
        tag: String,
        unmerged: Boolean = true,
    ): List<String> {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode()
        val out = mutableListOf<String>()

        fun collect(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotEmpty()) out += it.text }
            n.children.forEach(::collect)
        }
        collect(node)
        return out
    }

    /** Moves the slider [tag] to stop [index], as a screen reader's adjustment does. */
    private fun moveTo(
        tag: String,
        index: Int,
    ) {
        slider(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
        compose.waitForIdle()
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Keep history for %1\$s?", app.resources.getString(R.string.settings_keep_history_shorten_title))
        assertEquals(
            "Keep history for 30 days?",
            app.getString(R.string.settings_keep_history_shorten_title, "30 days"),
        )
        assertEquals(
            "What was played or written more than 30 days ago is deleted from this phone now. This cannot be undone.",
            app.getString(R.string.settings_keep_history_shorten_text, "30 days"),
        )
        assertEquals("Shorten", text(R.string.settings_keep_history_shorten))
        assertEquals("1 h", app.getString(R.string.settings_hours_short, 1))
    }

    @Test
    fun keepHistoryForLabelsEveryStopShowsItsValueAndIsReadByNameAndValue() =
        showHistory {
            slider(KEEP_HISTORY_SLIDER_TAG)
            assertEquals(listOf("7 days", "30 days", "90 days", "1 year", "Forever"), texts(KEEP_HISTORY_STOPS_TAG))
            assertEquals(listOf("90 days"), texts(KEEP_HISTORY_VALUE_TAG))
            assertEquals("Keep history for" to "90 days", read(KEEP_HISTORY_SLIDER_TAG))
        }

    @Test
    fun aLongerHistoryIsStoredAtOnceUpToForever() =
        showHistory {
            moveTo(KEEP_HISTORY_SLIDER_TAG, 3)
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == 365.days }
            assertTrue("no question for a longer time", !shows(text(R.string.settings_keep_history_shorten)))
            assertEquals("Keep history for" to "1 year", read(KEEP_HISTORY_SLIDER_TAG))
            moveTo(KEEP_HISTORY_SLIDER_TAG, 4)
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == null }
            assertEquals(listOf("Forever"), texts(KEEP_HISTORY_VALUE_TAG))
            assertEquals("Keep history for" to "Forever", read(KEEP_HISTORY_SLIDER_TAG))
        }

    @Test
    fun aShorterHistoryAsksFirstAndCancelPutsTheThumbBack() =
        showHistory {
            moveTo(KEEP_HISTORY_SLIDER_TAG, 1)
            compose.onNodeWithText("Keep history for 30 days?").assertExists()
            compose
                .onNodeWithText(
                    "What was played or written more than 30 days ago is deleted from this phone now. " +
                        "This cannot be undone.",
                ).assertExists()
            assertEquals("not stored before the answer", 90.days, settings.now().historyLifetime)
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertTrue(!shows("Keep history for 30 days?"))
            assertEquals(90.days, settings.now().historyLifetime)
            assertEquals("Keep history for" to "90 days", read(KEEP_HISTORY_SLIDER_TAG))
            assertEquals(listOf("90 days"), texts(KEEP_HISTORY_VALUE_TAG))
        }

    @Test
    fun aShorterHistoryIsStoredOnShorten() =
        showHistory {
            moveTo(KEEP_HISTORY_SLIDER_TAG, 0)
            compose.onNodeWithText("Keep history for 7 days?").assertExists()
            compose.onNodeWithText(text(R.string.settings_keep_history_shorten)).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == 7.days }
            assertTrue(!shows("Keep history for 7 days?"))
            assertEquals("Keep history for" to "7 days", read(KEEP_HISTORY_SLIDER_TAG))
        }

    @Test
    fun theIdleTriggerLabelsEveryStopInMinutesAndHoursAndAMoveIsStored() =
        showHistory {
            slider(SESSION_GAP_SLIDER_TAG)
            assertEquals(listOf("10 min", "20 min", "30 min", "1 h", "2 h"), texts(SESSION_GAP_STOPS_TAG))
            assertEquals(listOf("30 min"), texts(SESSION_GAP_VALUE_TAG))
            assertEquals("New session idle trigger" to "30 minutes", read(SESSION_GAP_SLIDER_TAG))
            moveTo(SESSION_GAP_SLIDER_TAG, 3)
            compose.waitUntil(WAIT_MS) { settings.now().sessionGap == 1.hours }
            assertEquals("New session idle trigger" to "1 hour", read(SESSION_GAP_SLIDER_TAG))
            assertEquals(listOf("1 h"), texts(SESSION_GAP_VALUE_TAG))
            moveTo(SESSION_GAP_SLIDER_TAG, 0)
            compose.waitUntil(WAIT_MS) { settings.now().sessionGap == 10.minutes }
        }

    @Test
    fun eachSourcesFindingsAreASliderLabelledAtItsEnds() =
        showHistory {
            val expected =
                mapOf(
                    SourceId.MUSICBRAINZ to ("MusicBrainz" to "30 days"),
                    SourceId.WIKIDATA to ("Wikidata" to "30 days"),
                    SourceId.WIKIPEDIA to ("Wikipedia" to "7 days"),
                    SourceId.LASTFM to ("Last.fm" to "7 days"),
                    SourceId.LRCLIB to ("LRCLIB" to "7 days"),
                    SourceId.WEB to ("Web search" to "1 day"),
                )
            for ((source, nameAndValue) in expected) {
                val tags: SteppedTags = lifetimeTags(source)
                slider(tags.slider)
                assertEquals(source.name, listOf("1 day", "90 days"), texts(tags.stops))
                assertEquals(source.name, listOf(nameAndValue.second), texts(tags.value))
                assertEquals(source.name, nameAndValue, read(tags.slider))
            }
            val web = lifetimeTags(SourceId.WEB)
            moveTo(web.slider, 2)
            compose.waitUntil(WAIT_MS) { settings.now().cacheLifetimes[SourceId.WEB] == 7.days }
            assertEquals("Web search" to "7 days", read(web.slider))
        }

    @Test
    fun aStoredValueThatIsNotAStopIsShownAndReadAsItIsAtTheNearestStop() {
        runBlocking {
            settings.update {
                it.copy(
                    historyLifetime = 12.hours,
                    sessionGap = 50.minutes,
                    cacheLifetimes = it.cacheLifetimes + (SourceId.WEB to 10.days),
                )
            }
        }
        showHistory {
            assertEquals("Keep history for" to "12 hours", read(KEEP_HISTORY_SLIDER_TAG))
            assertEquals(listOf("12 hours"), texts(KEEP_HISTORY_VALUE_TAG))
            assertEquals("New session idle trigger" to "50 minutes", read(SESSION_GAP_SLIDER_TAG))
            assertEquals(listOf("50 min"), texts(SESSION_GAP_VALUE_TAG))
            val web = lifetimeTags(SourceId.WEB)
            assertEquals("Web search" to "10 days", read(web.slider))
            assertEquals(listOf("10 days"), texts(web.value))
            // The thumbs at the nearest stops, the values not written.
            assertEquals(0f, progress(KEEP_HISTORY_SLIDER_TAG))
            assertEquals(3f, progress(SESSION_GAP_SLIDER_TAG))
            assertEquals(2f, progress(web.slider))
            assertEquals(12.hours, settings.now().historyLifetime)

            // Once moved, the stop's own words.
            moveTo(SESSION_GAP_SLIDER_TAG, 4)
            compose.waitUntil(WAIT_MS) { settings.now().sessionGap == 2.hours }
            assertEquals("New session idle trigger" to "2 hours", read(SESSION_GAP_SLIDER_TAG))
        }
    }

    /** Where the slider [tag]'s thumb is, by stop. */
    private fun progress(tag: String): Float? =
        slider(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            ?.current
}
