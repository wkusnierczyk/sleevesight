package app.sleevesight.app.ui.home

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.settings.Dismissals
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.settings.dismissals
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.MessageHost
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Dismissable
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Home with nothing playing (#627): a row per player *Players* lists, A to Z by name, the followed
 * ones opening the player, the switched-off ones linking to *Players*; and the guide to Home,
 * hidden once, until Home is left or a track plays, or dismissed for good, with *Undo* (#759; its
 * notes and its picture full screen: `GuideNotesTest`, `GuideMagnifierTest`).
 */
@RunWith(RobolectricTestRunner::class)
class IdleHomeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores by lazy { TestStores(tmp.root) }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val openedAt = mutableListOf<SettingsTarget>()

    // Two players whose names sort the other way from their packages: the order is by name.
    private val names = mapOf(ZULU to "Zulu", ALPHA to "Alpha", POWERAMP to "Poweramp")
    private val zuluLaunch = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(ZULU, "Main"))

    private fun actions(
        installed: InstalledPlayers? = null,
        hideGuideOnce: () -> Unit = {},
        dismissals: Dismissals = Dismissals.NONE,
    ) = NowPlayingActions(
        normalizer::normalize,
        playerLabel = { names[it] ?: it },
        playerLaunch = { if (it == ZULU) zuluLaunch else null },
        openSettingsAt = { openedAt += it },
        installedPlayers = installed,
        hideGuideOnce = hideGuideOnce,
        dismissals = dismissals,
    )

    private fun show(
        settings: Settings,
        actions: NowPlayingActions = actions(),
        hidden: Boolean = false,
        block: (MainActivity) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(
                            NowPlayingState(
                                PipelineView(),
                                playing = null,
                                settings = settings,
                                guideHidden = hidden,
                            ),
                            actions,
                        )
                    }
                }
            }
            compose.awaitIdleHome()
            scenario.onActivity { block(it) }
        }
    }

    /** Home with nothing playing, from [feed], as the app draws it: under its messages (#759). */
    private fun home(feed: HomeFeed) =
        @Composable {
            MessageHost {
                Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingHome(feed) }
            }
        }

    private fun feed(
        settings: StateFlow<Settings?> = MutableStateFlow(players),
        playing: StateFlow<NowPlaying?> = MutableStateFlow(null),
        hiding: GuideHiding = GuideHiding(),
        dismissals: Dismissals = Dismissals.NONE,
    ) = HomeFeed(
        view = MutableStateFlow(PipelineView()),
        nowPlaying = playing,
        playback = MutableStateFlow(PlaybackInfo.NONE),
        art = MutableStateFlow(null),
        settings = settings,
        normalizer = normalizer,
        playerLabel = { names[it] ?: it },
        playerActivity = { null },
        write = { _, _ -> },
        stop = {},
        guideHidden = hiding.hidden,
        hideGuideOnce = hiding::hide,
        homeLeft = hiding::homeLeft,
        dismissals = dismissals,
    )

    private fun guides() = compose.onAllNodesWithTag(HOME_GUIDE_TAG).fetchSemanticsNodes().size

    private fun hideOnce() {
        compose.onNodeWithTag(GUIDE_HIDE_TAG).performScrollTo().assert(hasText(text(R.string.home_guide_hide_once)))
        compose.onNodeWithTag(GUIDE_HIDE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(0, guides())
    }

    private val players = Settings(followedPlayers = mapOf(ZULU to true, ALPHA to true, POWERAMP to false))

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    @Test
    fun eachPlayerIsARowAToZByItsNameNotItsPackage() =
        show(players) {
            compose.onNodeWithText(text(R.string.home_play_on, "Alpha")).assertExists()
            compose.onNodeWithText(text(R.string.home_play_on, "Zulu")).assertExists()
            assertTrue(top(idlePlayerTag(ALPHA)) < top(idlePlayerTag(POWERAMP)))
            assertTrue(top(idlePlayerTag(POWERAMP)) < top(idlePlayerTag(ZULU)))
            // The idle line is gone.
            assertEquals(0, compose.onAllNodes(hasText("Lookups run", substring = true)).fetchSemanticsNodes().size)
        }

    @Test
    fun aFollowedPlayersRowIsAButtonThatOpensThePlayer() =
        show(players) { activity ->
            compose
                .onNodeWithTag(idlePlayerTag(ZULU))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performScrollTo()
                .performClick()
            assertEquals(zuluLaunch.component, shadowOf(activity).nextStartedActivity?.component)
        }

    @Test
    fun aSwitchedOffPlayersRowIsNotAButtonSaysSoAndLinksToPlayers() =
        show(players) {
            val row = compose.onNodeWithTag(idlePlayerTag(POWERAMP)).fetchSemanticsNode()
            assertFalse(row.config.contains(SemanticsActions.OnClick))
            compose.onNodeWithTag(idlePlayerTag(POWERAMP), useUnmergedTree = true).assertExists()
            val described =
                compose
                    .onAllNodes(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ContentDescription,
                            listOf(text(R.string.home_play_on_not_followed, "Poweramp")),
                        ),
                    ).fetchSemanticsNodes()
            assertEquals(1, described.size)
            compose.onNodeWithText(text(R.string.home_player_enable)).performScrollTo().performClick()
            assertEquals(listOf(SettingsTarget.players), openedAt)
        }

    @Test
    fun withNoPlayerSeenALineSaysSoAndLinksToPlayers() {
        // Stored, but neither shown by the phone nor seen: *Players* lists none, and so does Home.
        val installed = InstalledPlayers({ null }, { false }, MutableStateFlow(emptySet()))
        show(players, actions(installed)) {
            assertEquals(0, compose.onAllNodesWithTag(idlePlayerTag(ALPHA)).fetchSemanticsNodes().size)
            val line = text(R.string.home_players_none, text(R.string.home_players_none_link))
            compose.onNodeWithText(line).performScrollTo().performFirstLinkClick()
            assertEquals(listOf(SettingsTarget.players), openedAt)
        }
    }

    @Test
    fun aDismissedGuideIsNotShown() =
        show(players.copy(dismissed = setOf(Dismissable.HOME_GUIDE))) {
            assertEquals(0, guides())
            compose.onNodeWithTag(idlePlayerTag(ALPHA)).assertExists()
        }

    @Test
    fun aGuideHiddenOnceIsNotShown() =
        show(players, hidden = true) {
            assertEquals(0, guides())
            compose.onNodeWithTag(idlePlayerTag(ALPHA)).assertExists()
        }

    @Test
    fun hideOnceHidesTheGuideUntilATrackHasPlayed() {
        val hiding = GuideHiding()
        val playing = MutableStateFlow<NowPlaying?>(null)
        // As the app graph runs it, for the process.
        val clearing = CoroutineScope(Dispatchers.Unconfined).launch { hiding.clearOnPlay(playing) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent(content = home(feed(playing = playing, hiding = hiding))) }
                compose.awaitIdleHome()
                hideOnce()
                // Still hidden while nothing plays.
                compose.onNodeWithTag(idlePlayerTag(ALPHA)).assertExists()
                // A track plays, and ends: the guide is back.
                playing.value = sundown
                compose.waitForIdle()
                playing.value = null
                compose.awaitIdleHome()
                compose.onNodeWithTag(HOME_GUIDE_TAG).assertExists()
            }
        } finally {
            clearing.cancel()
        }
    }

    @Test
    fun hideOnceEndsWhenHomeIsLeftForAnotherScreenAndVisitedAgain() {
        val hiding = GuideHiding()
        val home = home(feed(hiding = hiding))
        lateinit var nav: NavHostController
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") { home() }
                        composable("elsewhere") { Text("Elsewhere") }
                    }
                }
            }
            compose.awaitIdleHome()
            hideOnce()
            scenario.onActivity { nav.navigate("elsewhere") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Elsewhere").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { nav.popBackStack() }
            compose.awaitIdleHome()
            compose.waitUntil(5_000) { guides() == 1 }
        }
    }

    @Test
    fun hideOnceEndsWhenTheAppGoesToTheBackgroundAndComesBack() {
        val hiding = GuideHiding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent(content = home(feed(hiding = hiding))) }
            compose.awaitIdleHome()
            hideOnce()
            // While Home is on screen, it stays hidden.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(hiding.hidden.value)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.awaitIdleHome()
            compose.waitUntil(5_000) { guides() == 1 }
        }
    }

    @Test
    fun hideOnceLastsThroughARotation() {
        val hiding = GuideHiding()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent(content = home(feed(hiding = hiding))) }
            compose.awaitIdleHome()
            hideOnce()
            // A configuration change stops the activity too, but Home is not left.
            scenario.recreate()
            assertTrue(hiding.hidden.value)
        }
    }

    @Test
    fun theSplitButtonSaysTheChoiceAndItsArrowNamesIt() {
        var once = 0
        val dismissed = mutableListOf<Dismissable>()
        val dismissals =
            Dismissals({
                dismissed += it
                CompletableDeferred(true)
            }, { CompletableDeferred(true) })
        show(players, actions(hideGuideOnce = { once++ }, dismissals = dismissals)) {
            fun says(choice: Int) {
                compose.onNodeWithTag(GUIDE_HIDE_TAG).assert(hasText(text(choice)))
                compose
                    .onNodeWithTag(GUIDE_HIDE_MENU_TAG)
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.ContentDescription,
                            listOf(text(R.string.home_guide_dismiss_options, text(choice))),
                        ),
                    )
            }
            // *Hide once* by default.
            says(R.string.home_guide_hide_once)
            compose.onNodeWithTag(GUIDE_HIDE_MENU_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GUIDE_DISMISS_TAG).performClick()
            says(R.string.home_guide_dismiss)
            compose.onNodeWithTag(GUIDE_HIDE_TAG).performScrollTo().performClick()
            assertEquals(listOf(Dismissable.HOME_GUIDE), dismissed)
            assertEquals(0, once)
            compose.onNodeWithTag(GUIDE_HIDE_MENU_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GUIDE_HIDE_ONCE_TAG).performClick()
            says(R.string.home_guide_hide_once)
            compose.onNodeWithTag(GUIDE_HIDE_TAG).performScrollTo().performClick()
            assertEquals(1, once)
            assertEquals(1, dismissed.size)
        }
    }

    @Test
    fun theMenuOffersHideOnceAndDismissOnly() =
        show(players) {
            compose.onNodeWithTag(GUIDE_HIDE_MENU_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GUIDE_HIDE_ONCE_TAG).assert(hasText(text(R.string.home_guide_hide_once)))
            compose.onNodeWithTag(GUIDE_DISMISS_TAG).assert(hasText(text(R.string.home_guide_dismiss)))
            assertEquals(0, compose.onAllNodesWithText("Re-enable in Settings").fetchSemanticsNodes().size)
        }

    @Test
    fun dismissPutsTheGuideAwayForGoodWithAMessageAndUndo() {
        val store = SettingsStore(stores.open("settings"))
        val shown = MutableStateFlow<Settings?>(null)
        val reading = CoroutineScope(Dispatchers.Unconfined).launch { store.settings.collect { shown.value = it } }
        try {
            runBlocking { store.update { players } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val feed = feed(settings = shown, dismissals = store.dismissals(saves))
                scenario.onActivity { it.setContent(content = home(feed)) }
                compose.awaitIdleHome()
                compose.onNodeWithTag(GUIDE_HIDE_MENU_TAG).performScrollTo().performClick()
                compose.onNodeWithTag(GUIDE_DISMISS_TAG).performClick()
                compose.onNodeWithTag(GUIDE_HIDE_TAG).performScrollTo().performClick()
                compose.waitUntil(5_000) { Dismissable.HOME_GUIDE in store.now().dismissed }
                compose.waitUntil(5_000) { guides() == 0 }
                val message = text(R.string.home_guide_dismissed)
                compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(text(R.string.undo)).performClick()
                compose.waitUntil(5_000) { store.now().dismissed.isEmpty() }
                compose.waitUntil(5_000) { guides() == 1 }
            }
        } finally {
            reading.cancel()
        }
    }

    private companion object {
        const val ZULU = "com.a.zzz"
        const val ALPHA = "com.z.aaa"
        const val POWERAMP = "com.maxmpz.audioplayer"
    }
}
