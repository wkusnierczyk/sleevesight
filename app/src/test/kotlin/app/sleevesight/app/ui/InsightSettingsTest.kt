package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.DESCRIPTION_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.PrepareNextTrack
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Insights* (#444): *Reactive*, a button group (#947), with *Crossfade* under it only while it is
 * *Skip*, each mode chosen there told to the pipeline (#625), and its description a table of the
 * modes (#945); *Saver* right after it, its count *Stop after* a button group (#947); *Proactive*,
 * a switch; *Don't repeat artist facts* (#407, #546), a switch; the rows in their order; and the
 * rows about writing fixed while *Show insights* is off.
 */
@RunWith(RobolectricTestRunner::class)
class InsightSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    // A button of a group, by its label.
    private fun button(label: String) = compose.onNode(hasText(label) and isSelectable())

    private fun button(id: Int) = button(text(id))

    private fun changes(count: Int) = app.resources.getQuantityString(R.plurals.settings_changes, count, count)

    // Where a text in *Reactive*'s description is drawn (the unmerged tree), whether on screen or not.
    private fun drawn(said: String): Rect {
        val node =
            compose
                .onNode(hasText(said) and hasAnyAncestor(hasTestTag(DESCRIPTION_TAG)), useUnmergedTree = true)
                .fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    // Where the row titled [id] starts, on screen or not.
    private fun top(id: Int) =
        compose
            .onNodeWithText(text(id))
            .fetchSemanticsNode()
            .positionInRoot.y

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = WAIT_MS) { condition() }

    // The modes chosen for *Reactive*, as the pipeline hears them (#625).
    private val chosen = mutableListOf<WriteAutomatically>()

    private fun show(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        chooseWritingMode =
                            settings.writingModeChooser(saves) { mode ->
                                chosen += mode
                                {}
                            },
                    ) { it }
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            block()
        }
    }

    @Test
    fun crossfadeShowsOnlyWhileReactiveIsOnSkip() =
        show {
            button(R.string.settings_write_every_change_short).performScrollTo().assertIsSelected()
            assertTrue(!shown(R.string.settings_crossfade))

            button(R.string.settings_write_when_skip_short).performScrollTo().performClick()
            waitFor("Skip stored") { settings.now().writeAutomatically == WriteAutomatically.WHEN_I_SKIP }
            waitFor("Crossfade shown") { shown(R.string.settings_crossfade) }
            button(R.string.settings_write_when_skip_short).assertIsSelected()
            compose.onNodeWithText(text(R.string.settings_write_when_skip_detail)).assertExists()

            button(R.string.settings_write_never_short).performScrollTo().performClick()
            waitFor("Demand stored") { settings.now().writeAutomatically == WriteAutomatically.NEVER }
            waitFor("Crossfade gone") { !shown(R.string.settings_crossfade) }
            // Under *Demand*, nothing is written away, so *Saver* and its count cannot be changed.
            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().assertIsNotEnabled()
            button(changes(Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER)).performScrollTo().assertIsNotEnabled()
        }

    @Test
    fun reactivesChoicesAreTheInsightCardsModesInOrderOfHowMuchIsWrittenAutomatically() =
        show {
            val lefts =
                listOf("Demand", "Skip", "Change").map { name ->
                    button(name)
                        .performScrollTo()
                        .fetchSemanticsNode()
                        .boundsInRoot.left
                }
            assertEquals(lefts.sorted(), lefts)
            // Under the buttons, what the chosen one, the default, does (#945).
            compose
                .onNodeWithText(
                    "Writes automatically on every track change: the key insight, and Song, Artist or Album " +
                        "if left open.",
                ).assertExists()
        }

    @Test
    fun reactivesDescriptionIsATableOfTheModesShowingItsFirstLineFolded() =
        show {
            val lead = "What triggers writing an insight."
            val modes =
                listOf(
                    "Demand" to "Only when you tap a button with the AI mark.",
                    "Skip" to "Automatically when you skip to a track, not when one follows another by itself.",
                    "Change" to "Automatically on every track change.",
                )
            val end = "Skip and Change will begin triggering insights from the next track."
            // Folded, its first line alone, though a screen reader reads it whole.
            val description = compose.onNode(hasTestTag(DESCRIPTION_TAG) and hasText(lead)).performScrollTo()
            val whole = (listOf(lead) + modes.map { (mode, means) -> "$mode: $means" } + end).joinToString(" ")
            assertEquals(listOf(whole), description.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
            assertTrue(compose.onAllNodesWithText(modes.first().second).fetchSemanticsNodes().isEmpty())

            description.performClick()
            waitFor("unfolded") {
                compose.onAllNodesWithText(end, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            // The modes right-aligned in a column, what each means left-aligned after them.
            val terms = modes.map { (mode, _) -> drawn(mode) }
            val meanings = modes.map { (_, means) -> drawn(means) }
            assertEquals(1, terms.map { it.right }.distinct().size)
            assertEquals(1, meanings.map { it.left }.distinct().size)
            assertTrue(meanings.first().left > terms.first().right)
            val tops = terms.map { it.top }
            assertEquals(tops.sorted(), tops)
            assertTrue(drawn(lead).bottom <= tops.first())
            assertTrue(drawn(end).top >= meanings.last().bottom)
        }

    @Test
    fun eachModeChosenIsToldToThePipelineTheOneChosenAlreadyIncluded() =
        show {
            // The default, Change, chosen again: still the first action (#625).
            val names =
                mapOf(
                    WriteAutomatically.ON_EVERY_TRACK_CHANGE to R.string.settings_write_every_change_short,
                    WriteAutomatically.NEVER to R.string.settings_write_never_short,
                )
            for ((mode, name) in names) {
                button(name).performScrollTo().performClick()
                waitFor("$mode stored") { settings.now().writeAutomatically == mode }
            }
            waitFor("both told") { chosen.size == 2 }
            assertEquals(listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.NEVER), chosen)
        }

    @Test
    fun saverComesRightAfterReactiveAndCrossfade() {
        runBlocking { settings.update { it.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP) } }
        show {
            val order =
                listOf(
                    R.string.settings_write_insights,
                    R.string.settings_prepare_next,
                    R.string.settings_background,
                    R.string.settings_write_automatically,
                    R.string.settings_crossfade,
                    R.string.settings_pause_auto_write,
                    R.string.settings_pause_auto_write_after,
                    R.string.settings_artist_facts_once,
                )
            val tops = order.map(::top)
            assertEquals(tops.sorted(), tops)
            // Neither *Saver* nor *Stop after* is indented: both start where *Reactive* does.
            val starts =
                listOf(
                    R.string.settings_write_automatically,
                    R.string.settings_pause_auto_write,
                    R.string.settings_pause_auto_write_after,
                ).map {
                    compose
                        .onNodeWithText(text(it))
                        .performScrollTo()
                        .fetchSemanticsNode()
                        .boundsInRoot.left
                }
            assertEquals(1, starts.distinct().size)
        }
    }

    @Test
    fun stopAftersCountsAreAButtonGroupWithAStoredCountOutsideThemOffered() {
        runBlocking { settings.update { it.copy(pauseAutoWriteAfter = 7) } }
        show {
            val lefts =
                listOf(3, 5, 7, 10).map {
                    button(changes(it))
                        .performScrollTo()
                        .fetchSemanticsNode()
                        .boundsInRoot.left
                }
            assertEquals(lefts.sorted(), lefts)
            button(changes(7)).assertIsSelected()
            button(changes(3)).performClick()
            waitFor("a pause after 3 stored") { settings.now().pauseAutoWriteAfter == 3 }
            button(changes(3)).assertIsSelected()
        }
    }

    @Test
    fun proactiveIsASwitchForTheNextTracksEvidence() =
        show {
            val proactive = compose.onNodeWithText(text(R.string.settings_prepare_next)).performScrollTo()
            proactive.assertIsOn()
            proactive.performClick()
            waitFor("Proactive stored off") { settings.now().prepareNextTrack == PrepareNextTrack.OFF }
            proactive.assertIsOff()
            proactive.performClick()
            waitFor("Proactive stored on") { settings.now().prepareNextTrack == PrepareNextTrack.EVIDENCE }
        }

    @Test
    fun artistFactsOncePerSessionIsASwitchOnByDefault() =
        show {
            val once = compose.onNodeWithText(text(R.string.settings_artist_facts_once)).performScrollTo()
            once.assertIsOn()
            val summary =
                app.getString(
                    R.string.settings_artist_facts_once_summary,
                    text(R.string.settings_session_gap),
                    text(R.string.settings_section_history),
                )
            compose.onNodeWithText(summary).assertIsDisplayed()
            once.performClick()
            waitFor("facts on every track stored") { !settings.now().artistFactsOncePerSession }
            once.assertIsOff()
            once.performClick()
            waitFor("facts once per session stored") { settings.now().artistFactsOncePerSession }
        }

    @Test
    fun withShowInsightsOffTheRowsAboutWritingAreFixed() {
        runBlocking { settings.update { it.copy(writeInsights = false) } }
        show {
            compose.onNodeWithText(text(R.string.settings_write_insights)).performScrollTo().assertIsOff()
            for (mode in listOf("Demand", "Skip", "Change")) button(mode).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.settings_artist_facts_once)).performScrollTo().assertIsNotEnabled()
        }
    }
}
