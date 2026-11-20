package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.PlaybackState
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.Transport
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.PlayerApps
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The followed player in Home's header (#1409, #1209, #1440): the record plays and pauses it, only as
 * it advertises, and is no button otherwise, and a long press on it does nothing; the player symbol
 * opens the player menu, whose controls and choice of player leave it open and whose *Open <player>*
 * closes it (a tap outside and Back close it as any Material menu), and a long press opens the player;
 * with nothing playing or no player switched on, the symbol is dimmed and opens a panel. Drawn as
 * on a phone, light and dark, at the default font size and twice it, the menu closed and open; with
 * `SLEEVESIGHT_PICTURES` set, each is also saved there as a picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderPlayerTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val playback = MutableStateFlow(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = ALL))
    private val followed = MutableStateFlow(mapOf(SPOTIFY to PlaybackStatus.PLAYING, YOUTUBE to PlaybackStatus.PAUSED))
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(followedPlayers = mapOf(SPOTIFY to true, YOUTUBE to true, CASTBOX to true)),
        )
    private val sent = mutableListOf<Transport>()
    private val chosen = mutableListOf<String>()
    private var activity: PendingIntent? = null
    private val askedScreen = mutableListOf<String>()
    private val track = MutableStateFlow<NowPlaying?>(NowPlaying("Sundown", "Gordon Lightfoot", player = SPOTIFY))

    // The player last shown, as the session layer keeps it (#1440).
    private val lastPlayer = MutableStateFlow<String?>(SPOTIFY)

    // Whether Home is composed; off, as when the settings are open over it.
    private val homeShown = mutableStateOf(true)

    // The phone's animator duration scale, as the app's root provides it.
    private val scale = mutableFloatStateOf(1f)

    private val feed = feedOf(installed = null)

    // The feed, with the phone's players as [installed] says (null: every player listed).
    private fun feedOf(installed: InstalledPlayers?) =
        HeaderPlayerFeed(
            playback = playback,
            followed = followed,
            settings = settings,
            label = { NAMES.getValue(it) },
            activity = { player ->
                askedScreen += player
                activity.takeIf { player == SPOTIFY }
            },
            launch = { Intent(Intent.ACTION_MAIN).setPackage(it) },
            installed = installed,
            send = { sent += it },
            follow = {
                chosen += it
                playback.value = PlaybackInfo(it, followed.value.getValue(it), actions = ALL)
            },
            nowPlaying = track,
            ad = MutableStateFlow<AdPlaying?>(null),
            lastPlayer = lastPlayer,
        )

    private fun show(
        dark: Boolean = false,
        openSettingsAt: (SettingsTarget) -> Unit = {},
        shownFeed: HeaderPlayerFeed = feed,
        block: (MainActivity) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalAnimatorDurationScale provides scale.floatValue) {
                            if (homeShown.value) {
                                HomeScreen(
                                    accessGranted = true,
                                    onOpenFirstRun = {},
                                    onOpenSettings = {},
                                    player = shownFeed,
                                    onOpenSettingsAt = openSettingsAt,
                                ) {
                                    Column {
                                        for (i in 0 until LINES) Text("Line $i", modifier = Modifier.testTag("line$i"))
                                    }
                                }
                            } else {
                                Text("Elsewhere")
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity(block)
        }
    }

    private fun openMenu() {
        compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performClick()
        compose.onNodeWithTag(PLAYER_MENU_OPEN_TAG).assertExists()
    }

    // No session at all.
    private fun nothingPlaying() {
        playback.value = PlaybackInfo.NONE.copy(actions = ALL)
        followed.value = emptyMap()
        track.value = null
    }

    // The symbol, read as *Player menu*, a button, dimmed with [state] said, or not dimmed with none.
    private fun symbolReads(state: String?) {
        val symbol = compose.onNodeWithTag(PLAYER_SYMBOL_TAG)
        val named = listOf(text(R.string.home_player_symbol))
        symbol
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, named))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val said = symbol.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(state, said)
    }

    private fun menuOpen() = compose.onNodeWithTag(PLAYER_MENU_OPEN_TAG).assertExists()

    private fun menuClosed() = compose.onNodeWithTag(PLAYER_MENU_OPEN_TAG).assertDoesNotExist()

    private fun scrollToTheEnd() {
        compose.onNodeWithTag("line${LINES - 1}").performScrollTo()
        compose.onNodeWithTag("line0").assertIsNotDisplayed()
    }

    @Test
    fun theRecordPausesAPlayingPlayerAndPlaysAPausedOne() =
        show {
            compose
                .onNodeWithContentDescription(text(R.string.home_record_pause))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, actions = ALL)
            compose.onNodeWithContentDescription(text(R.string.home_record_play)).performClick()
            assertEquals(listOf(Transport.PAUSE, Transport.PLAY), sent)
        }

    @Test
    fun aTapOnTheRecordPlaysOrPausesAndDoesNotScrollHome() =
        show {
            scrollToTheEnd()
            compose.onNodeWithTag(RECORD_TAG).performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals(listOf(Transport.PAUSE), sent)
            compose.onNodeWithTag("line0").assertIsNotDisplayed()
            // The words still scroll Home to the top.
            compose
                .onNodeWithContentDescription(text(R.string.home_scroll_to_top, text(R.string.app_name)))
                .performClick()
            compose.onNodeWithTag("line0").assertIsDisplayed()
        }

    @Test
    fun aPlayerAdvertisingNoPlayOrPauseHasNoRecordButtonAndATapDoesNothing() {
        playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = PlaybackState.ACTION_SKIP_TO_NEXT)
        show {
            assertEquals(0, buttons(R.string.home_record_pause) + buttons(R.string.home_record_play))
            scrollToTheEnd()
            compose.onNodeWithTag(RECORD_TAG, useUnmergedTree = true).performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals(emptyList<Transport>(), sent)
            compose.onNodeWithTag("line0").assertIsNotDisplayed()
        }
    }

    @Test
    fun withNothingPlayingTheSymbolIsDimmedAndOpensAPanelAndThereIsNoRecordButton() {
        nothingPlaying()
        show {
            symbolReads(text(R.string.home_player_symbol_nothing_playing_state))
            assertEquals(0, buttons(R.string.home_record_pause) + buttons(R.string.home_record_play))
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performClick()
            compose.onNodeWithText(text(R.string.home_player_symbol_nothing_playing)).assertExists()
            menuClosed()
            compose.onNodeWithTag(SYMBOL_PANEL_DISMISS_TAG).performClick()
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertDoesNotExist()
            // It opens again on the next tap.
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performClick()
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertExists()
        }
    }

    @Test
    fun aLongPressWithNothingPlayingOpensTheFirstPlayerSwitchedOn() {
        nothingPlaying()
        lastPlayer.value = null
        show {
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            // Castbox, Spotify, YouTube Music, A to Z: Castbox first.
            assertEquals(CASTBOX, compose.nextStarted())
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun aLongPressOnceAPlayerStoppedOpensTheLastPlayerFollowed() =
        show {
            nothingPlaying()
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            assertEquals(SPOTIFY, compose.nextStarted())
        }

    @Test
    fun withNoPlayerSwitchedOnTheSymbolIsDimmedAndATapOrALongPressLinksToPlayers() {
        nothingPlaying()
        settings.value = Settings(followedPlayers = mapOf(SPOTIFY to false, YOUTUBE to false))
        val opened = mutableListOf<SettingsTarget>()
        show(openSettingsAt = { opened += it }) {
            // Said once the players have loaded, off the main thread (#1469).
            symbolSays(noneEnabled())
            symbolReads(noneEnabled())
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertExists()
            assertEquals(null, shadowOf(app).nextStartedActivity)
            compose.onNodeWithTag(SYMBOL_PANEL_DISMISS_TAG).performClick()
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertDoesNotExist()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performClick()
            val link = text(R.string.home_players_none_link)
            compose.onNodeWithText(text(R.string.home_player_symbol_none_enabled, link)).performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.players), opened)
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun aLongPressWithAPlayerOpensIt() {
        activity = PendingIntent.getActivity(app, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
        show {
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            compose.waitForIdle()
            menuClosed()
            assertEquals(listOf(SPOTIFY), askedScreen)
            val started = shadowOf(it).nextStartedActivity ?: shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started?.action)
        }
    }

    // Codex review of #1441: the player last followed is the session layer's, so one that played and
    // stopped while Home was away is the one a long press opens on return.
    @Test
    fun theLastPlayerFollowedIsKeptWhileHomeIsAway() =
        show {
            homeShown.value = false
            compose.waitForIdle()
            playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL)
            lastPlayer.value = YOUTUBE
            nothingPlaying()
            homeShown.value = true
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            assertEquals(YOUTUBE, compose.nextStarted())
        }

    @Test
    fun aLongPressOnTheRecordDoesNothing() =
        show {
            // To a screen reader too: a tap, and no long press.
            val record = compose.onNodeWithTag(RECORD_TAG).fetchSemanticsNode().config
            assertTrue(SemanticsActions.OnClick in record)
            assertFalse(SemanticsActions.OnLongClick in record)
            compose.onNodeWithTag(RECORD_TAG).performTouchInput { longClick(center) }
            compose.waitForIdle()
            assertEquals(emptyList<Transport>(), sent)
            menuClosed()
            assertEquals(null, shadowOf(app).nextStartedActivity)
        }

    // Claude review of #1441: with *Settings*' defaults (Spotify on) and Spotify not on the phone,
    // no player *Settings → Players* lists is on: the symbol says so and links there.
    @Test
    fun withTheDefaultsAndSpotifyNotOnThePhoneNoPlayerIsEnabled() {
        nothingPlaying()
        lastPlayer.value = null
        settings.value = Settings()
        val phone = InstalledPlayers(icon = { null }, visible = { false }, seen = MutableStateFlow(emptySet()))
        show(shownFeed = feedOf(phone)) {
            symbolSays(noneEnabled())
            symbolReads(noneEnabled())
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertExists()
            assertEquals(null, shadowOf(app).nextStartedActivity)
        }
    }

    // Claude review of #1441: the last player followed, since switched off, gives way to the first
    // switched on.
    @Test
    fun aLongPressWithTheLastPlayerSwitchedOffOpensTheFirstSwitchedOn() {
        nothingPlaying()
        lastPlayer.value = YOUTUBE
        settings.value = Settings(followedPlayers = mapOf(SPOTIFY to true, YOUTUBE to false, CASTBOX to true))
        show {
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            assertEquals(CASTBOX, compose.nextStarted())
        }
    }

    // Claude review of #1441: with players switched on but none listed on the phone, and no player
    // followed before, a long press has nothing to open: the panel says no player is enabled.
    @Test
    fun aLongPressWithNothingToOpenOpensThePanel() {
        nothingPlaying()
        lastPlayer.value = null
        val phone = InstalledPlayers(icon = { null }, visible = { false }, seen = MutableStateFlow(emptySet()))
        show(shownFeed = feedOf(phone)) {
            compose.symbolReady()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performTouchInput { longClick(center) }
            // The panel that says none is enabled.
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertExists()
            symbolReads(noneEnabled())
            assertEquals(null, shadowOf(app).nextStartedActivity)
        }
    }

    private fun noneEnabled() = text(R.string.home_player_symbol_none_enabled_state)

    // The symbol's state said as [state], waited for: the players load off the main thread.
    private fun symbolSays(state: String) =
        compose.waitUntil(WAIT_MS) {
            compose
                .onAllNodes(hasTestTag(PLAYER_SYMBOL_TAG))
                .fetchSemanticsNodes()
                .any { it.config.getOrElseNullable(SemanticsProperties.StateDescription) { null } == state }
        }

    @Test
    fun theSymbolIsReadAsPlayerMenuWithItsActionsAndOpensTheMenu() =
        show {
            symbolReads(null)
            val symbol = compose.onNodeWithTag(PLAYER_SYMBOL_TAG).fetchSemanticsNode().config
            assertEquals(text(R.string.home_player_symbol_open_menu), symbol[SemanticsActions.OnClick].label)
            assertEquals(text(R.string.home_player_symbol_open_player), symbol[SemanticsActions.OnLongClick].label)
            openMenu()
            compose
                .onNodeWithText(text(R.string.home_player_state, "Spotify", text(R.string.home_state_playing)))
                .assertExists()
            compose.onNodeWithContentDescription(text(R.string.home_previous)).assertIsEnabled()
            compose.onNodeWithTag(transportTag(Transport.PAUSE)).assertIsEnabled()
            compose.onNodeWithContentDescription(text(R.string.home_next)).assertIsEnabled()
            compose.onNodeWithText(text(R.string.home_open_player, "Spotify")).assertExists()
            // The player's own icon on *Open <player>* (#1440).
            compose.onNodeWithTag(PLAYER_MENU_OPEN_PLAYER_ICON_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithText(text(R.string.home_players)).assertExists()
            compose
                .onNodeWithContentDescription("Spotify, ${text(R.string.home_state_playing)}")
                .assertIsSelected()
            compose.onNodeWithContentDescription("YouTube Music, ${text(R.string.home_state_paused)}").assertExists()
            compose.onNodeWithContentDescription("Castbox, ${text(R.string.home_player_open_app)}").assertExists()
        }

    @Test
    fun theMenusControlsSendAndLeaveItOpen() =
        show {
            openMenu()
            compose.onNodeWithTag(transportTag(Transport.NEXT)).performClick()
            menuOpen()
            compose.onNodeWithTag(transportTag(Transport.PREVIOUS)).performClick()
            menuOpen()
            compose.onNodeWithTag(transportTag(Transport.PAUSE)).performClick()
            menuOpen()
            assertEquals(listOf(Transport.NEXT, Transport.PREVIOUS, Transport.PAUSE), sent)
        }

    @Test
    fun aPlayerAdvertisingNoControlsHasThemDimmedAndSaysSo() {
        playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING)
        show {
            openMenu()
            for (control in listOf(Transport.PREVIOUS, Transport.PAUSE, Transport.NEXT)) {
                compose.onNodeWithTag(transportTag(control)).assertIsNotEnabled()
            }
            compose.onNodeWithText(text(R.string.home_player_no_controls, "Spotify")).assertExists()
        }
    }

    @Test
    fun choosingAPlayerWithASessionFollowsItAndTheMenuStaysOpenOnIt() =
        show {
            openMenu()
            compose.onNodeWithTag(menuPlayerTag(YOUTUBE)).performClick()
            assertEquals(listOf(YOUTUBE), chosen)
            menuOpen()
            compose
                .onNodeWithText(text(R.string.home_player_state, "YouTube Music", text(R.string.home_state_paused)))
                .assertExists()
            compose.onNodeWithTag(transportTag(Transport.PLAY)).assertIsEnabled()
            compose
                .onNodeWithContentDescription("YouTube Music, ${text(R.string.home_state_paused)}")
                .assertIsSelected()
        }

    @Test
    fun choosingAPlayerWithNoSessionOpensItsAppAndTheMenuStaysOpen() =
        show {
            openMenu()
            compose.onNodeWithTag(menuPlayerTag(CASTBOX)).performClick()
            assertEquals(emptyList<String>(), chosen)
            val started = shadowOf(app).nextStartedActivity
            assertEquals(CASTBOX, started?.`package`)
            menuOpen()
        }

    @Test
    fun openThePlayerOpensItsScreenAndClosesTheMenu() {
        activity = PendingIntent.getActivity(app, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
        show {
            openMenu()
            compose.onNodeWithTag(PLAYER_MENU_OPEN_PLAYER_TAG).performClick()
            menuClosed()
            // The menu's player's own screen, asked by its name (review of #1417).
            assertEquals(listOf(SPOTIFY), askedScreen)
            val started = shadowOf(it).nextStartedActivity ?: shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started?.action)
        }
    }

    @Test
    fun aSecondTapOnTheSymbolClosesTheMenu() =
        show {
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performSemanticsAction(SemanticsActions.OnClick)
            menuOpen()
            compose.onNodeWithTag(PLAYER_SYMBOL_TAG).performSemanticsAction(SemanticsActions.OnClick)
            menuClosed()
        }

    @Test
    fun withOnePlayerListedTheMenuHasNoPlayersList() {
        settings.value = Settings(followedPlayers = mapOf(SPOTIFY to true))
        show {
            openMenu()
            compose.onNodeWithText(text(R.string.home_players)).assertDoesNotExist()
        }
    }

    // Review of #1417: a session kept with its track cleared leaves Home idle, with no record button;
    // the symbol still opens the menu on its player (#1440).
    @Test
    fun aSessionWithNoTrackHasNoRecordButtonAndTheSymbolOpensTheMenu() {
        track.value = null
        show {
            assertEquals(0, buttons(R.string.home_record_pause) + buttons(R.string.home_record_play))
            symbolReads(null)
            openMenu()
        }
    }

    // Review of #1417: choosing a player whose session holds no track leaves Home idle, and the menu
    // open on that player all the same.
    @Test
    fun choosingAPlayerWithNoTrackKeepsTheMenuOpenOnIt() =
        show {
            openMenu()
            compose.onNodeWithTag(menuPlayerTag(YOUTUBE)).performClick()
            track.value = null
            compose.waitForIdle()
            menuOpen()
            compose
                .onNodeWithText(text(R.string.home_player_state, "YouTube Music", text(R.string.home_state_paused)))
                .assertExists()
        }

    // A player switched off in Settings → Players is never in the menu, whatever *Only enabled players
    // on Home* says; one switched on is, playing or not.
    @Test
    fun aPlayerSwitchedOffIsNeverInTheMenu() {
        for (onlyEnabled in listOf(false, true)) {
            settings.value =
                Settings(
                    followedPlayers = mapOf(SPOTIFY to true, YOUTUBE to true, CASTBOX to true, OFF to false),
                    onlyEnabledPlayersOnHome = onlyEnabled,
                )
            show {
                openMenu()
                compose.onNodeWithTag(menuPlayerTag(CASTBOX)).assertExists()
                compose.onNodeWithContentDescription("Castbox, ${text(R.string.home_player_open_app)}").assertExists()
                compose.onNodeWithTag(menuPlayerTag(OFF)).assertDoesNotExist()
            }
        }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                show(dark) { save("header-$theme-$size") }
                // The menu's popup is not drawn into a capture here: its content, drawn as the menu
                // draws it, on the menu's surface, the second player chosen.
                menuPicture(dark, "menu-$theme-$size", playback.value)
                menuPicture(dark, "menu-no-controls-$theme-$size", PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING))
            }
        }
    }

    private fun menuPicture(
        dark: Boolean,
        name: String,
        shown: PlaybackInfo,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize()) {
                                Surface(
                                    Modifier.padding(16.dp).width(280.dp),
                                    shape = MenuDefaults.shape,
                                    color = MenuDefaults.containerColor,
                                    tonalElevation = MenuDefaults.TonalElevation,
                                    shadowElevation = MenuDefaults.ShadowElevation,
                                ) {
                                    Column(Modifier.padding(vertical = 8.dp)) {
                                        PlayerMenu(feed, shown, "Spotify", PlayerApps(feed.label, feed.launch, null)) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // The players load off the main thread.
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodes(hasTestTag(menuPlayerTag(CASTBOX))).fetchSemanticsNodes().isNotEmpty()
            }
            save(name)
        }
    }

    // Each window drawn now (Home, and the menu's popup once open), as pictures named [name].
    private fun save(name: String) {
        val dir = out ?: return
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        for (i in roots.indices) {
            val bitmap = compose.onAllNodes(isRoot())[i].captureToImage().asAndroidBitmap()
            File(dir, "$name-$i.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun buttons(label: Int): Int =
        compose
            .onAllNodesWithContentDescription(text(label))
            .fetchSemanticsNodes()
            .count { it.config.contains(SemanticsProperties.Role) }

    private companion object {
        const val SPOTIFY = Settings.SPOTIFY
        const val YOUTUBE = "com.google.android.apps.youtube.music"
        const val CASTBOX = "fm.castbox.audiobook.radio.podcast"
        const val OFF = "com.example.switched.off"
        val NAMES = mapOf(SPOTIFY to "Spotify", YOUTUBE to "YouTube Music", CASTBOX to "Castbox", OFF to "Off Player")
        const val ALL =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        const val LINES = 80
        const val WAIT_MS = 5_000L
    }
}
