package app.sleevesight.app.ui.home

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
 * ones opening the player, the switched-off ones linking to *Players*; and the guide to Home, its
 * picture one image for a screen reader, dismissed once or for good.
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
        dismissGuide: () -> Unit = {},
        hideGuide: () -> Unit = {},
    ) = NowPlayingActions(
        normalizer::normalize,
        playerLabel = { names[it] ?: it },
        playerLaunch = { if (it == ZULU) zuluLaunch else null },
        openSettingsAt = { openedAt += it },
        installedPlayers = installed,
        dismissGuide = dismissGuide,
        hideGuide = hideGuide,
    )

    private fun show(
        settings: Settings,
        actions: NowPlayingActions = actions(),
        dismissed: Boolean = false,
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
                                guideDismissed = dismissed,
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
    fun theGuidesPictureIsOneImageWithItsNotesInOrder() =
        show(players) {
            val notes =
                listOf(
                    R.string.home_guide_note_settings,
                    R.string.home_guide_note_player,
                    R.string.home_guide_note_genres,
                    R.string.home_guide_note_insight,
                    R.string.home_guide_note_cite,
                    R.string.home_guide_note_write,
                    R.string.home_guide_note_sources,
                ).map { text(it) }
            val node = compose.onNodeWithTag(GUIDE_PICTURE_TAG).fetchSemanticsNode()
            assertEquals(
                listOf("Home while a track plays: " + notes.joinToString("; ")),
                node.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            // One image: nothing in it is read, or reached, on its own.
            assertTrue(node.children.isEmpty())
            assertEquals(0, compose.onAllNodes(hasText("Blue in Green")).fetchSemanticsNodes().size)
        }

    @Test
    fun withHomeGuideOffTheGuideIsNotShown() =
        show(players.copy(homeGuide = false)) {
            assertEquals(0, compose.onAllNodesWithTag(HOME_GUIDE_TAG).fetchSemanticsNodes().size)
            compose.onNodeWithTag(idlePlayerTag(ALPHA)).assertExists()
        }

    @Test
    fun dismissOnceHidesTheGuideUntilATrackHasPlayed() {
        val dismissal = GuideDismissal()
        val playing = MutableStateFlow<NowPlaying?>(null)
        val feed =
            HomeFeed(
                view = MutableStateFlow(PipelineView()),
                nowPlaying = playing,
                playback = MutableStateFlow(PlaybackInfo.NONE),
                art = MutableStateFlow(null),
                settings = MutableStateFlow(players),
                normalizer = normalizer,
                playerLabel = { names[it] ?: it },
                playerActivity = { null },
                write = { _, _ -> },
                stop = {},
                guideDismissed = dismissal.dismissed,
                dismissGuide = dismissal::dismiss,
            )
        // As the app graph runs it, for the process.
        val clearing = CoroutineScope(Dispatchers.Unconfined).launch { dismissal.clearOnPlay(playing) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingHome(feed) } }
                }
                compose.awaitIdleHome()
                compose.onNodeWithTag(GUIDE_DISMISS_TAG).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(0, compose.onAllNodesWithTag(HOME_GUIDE_TAG).fetchSemanticsNodes().size)
                // Still dismissed while nothing plays.
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
    fun doNotShowAgainSwitchesHomeGuideOff() {
        val store = SettingsStore(stores.open("settings"))
        var once = 0
        show(players, actions(dismissGuide = { once++ }, hideGuide = store.homeGuideHider(saves))) {
            compose.onNodeWithTag(GUIDE_DISMISS_MENU_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GUIDE_DISMISS_NEVER_TAG).performClick()
            // The button still says *Dismiss*; its arrow says what it does.
            compose.onNodeWithTag(GUIDE_DISMISS_TAG).assert(hasText(text(R.string.home_guide_dismiss)))
            compose
                .onNodeWithTag(GUIDE_DISMISS_MENU_TAG)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_guide_dismiss_options, text(R.string.home_guide_dismiss_never))),
                    ),
                )
            compose.onNodeWithTag(GUIDE_DISMISS_TAG).performScrollTo().performClick()
            compose.waitUntil(5_000) { !store.now().homeGuide }
            assertEquals(0, once)
        }
    }

    @Test
    fun dismissIsDismissOnceByDefault() {
        var once = 0
        var never = 0
        show(players, actions(dismissGuide = { once++ }, hideGuide = { never++ })) {
            compose
                .onNodeWithTag(GUIDE_DISMISS_MENU_TAG)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_guide_dismiss_options, text(R.string.home_guide_dismiss_once))),
                    ),
                )
            compose.onNodeWithTag(GUIDE_DISMISS_TAG).performScrollTo().performClick()
            assertEquals(1, once)
            assertEquals(0, never)
        }
    }

    @Test
    fun reEnableInSettingsOpensTheSettingsAtHomeGuide() =
        show(players) {
            compose.onNodeWithTag(GUIDE_DISMISS_MENU_TAG).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.home_guide_reenable)).performClick()
            assertEquals(listOf(SettingsTarget.homeGuide), openedAt)
            // The menu closed.
            assertEquals(0, compose.onAllNodesWithTag(GUIDE_DISMISS_NEVER_TAG).fetchSemanticsNodes().size)
        }

    private companion object {
        const val ZULU = "com.a.zzz"
        const val ALPHA = "com.z.aaa"
        const val POWERAMP = "com.maxmpz.audioplayer"
    }
}
