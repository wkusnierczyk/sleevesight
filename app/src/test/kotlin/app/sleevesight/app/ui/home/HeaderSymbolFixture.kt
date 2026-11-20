package app.sleevesight.app.ui.home

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.PlaybackState
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.session.AdPlaying
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Home's header with a followed player (#1440, #1445), for the tests of the note symbol: Spotify
 * playing [track] by default, YouTube Music paused beside it, Castbox switched on with no session;
 * each state a flow the test moves, the phone's animator duration scale ([scale]) included.
 */
internal class HeaderSymbolFixture(
    private val compose: ComposeTestRule,
) {
    val playback = MutableStateFlow(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = ALL))
    val followed = MutableStateFlow(mapOf(SPOTIFY to PlaybackStatus.PLAYING, YOUTUBE to PlaybackStatus.PAUSED))
    val settings =
        MutableStateFlow<Settings?>(
            Settings(followedPlayers = mapOf(SPOTIFY to true, YOUTUBE to true, CASTBOX to true)),
        )
    val track = MutableStateFlow<NowPlaying?>(song(SUNDOWN, play = 1))
    val lastPlayer = MutableStateFlow<String?>(SPOTIFY)
    val scale = mutableFloatStateOf(1f)
    val askedScreen = mutableListOf<String>()
    var activity: PendingIntent? = null

    /** Holds the players' load, off the main thread, while it is not released (#1469). */
    var loading: CountDownLatch? = null

    val feed =
        HeaderPlayerFeed(
            playback = playback,
            followed = followed,
            settings = settings,
            label = { NAMES.getValue(it) },
            activity = { player ->
                askedScreen += player
                activity.takeIf { player == SPOTIFY }
            },
            launch = {
                loading?.await(WAIT_MS, TimeUnit.MILLISECONDS)
                Intent(Intent.ACTION_MAIN).setPackage(it)
            },
            installed = null,
            send = {},
            follow = { playback.value = PlaybackInfo(it, followed.value.getValue(it), actions = ALL) },
            nowPlaying = track,
            ad = MutableStateFlow<AdPlaying?>(null),
            lastPlayer = lastPlayer,
        )

    /** Home with the header, [dark] or light, then [block] on its activity. */
    fun show(
        dark: Boolean = false,
        block: (MainActivity) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalAnimatorDurationScale provides scale.floatValue) {
                            HomeScreen(
                                accessGranted = true,
                                onOpenFirstRun = {},
                                onOpenSettings = {},
                                player = feed,
                                onOpenSettingsAt = {},
                            ) { Text("Home") }
                        }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity(block)
        }
    }

    /** No session at all. */
    fun nothingPlaying() {
        playback.value = PlaybackInfo.NONE.copy(actions = ALL)
        followed.value = emptyMap()
        track.value = null
    }

    /**
     * Waits until the note symbol takes a gesture: while none plays, not before the players have
     * loaded, off the main thread (#1469).
     */
    fun symbolReady() =
        compose.waitUntil(WAIT_MS) {
            compose
                .onAllNodes(hasTestTag(PLAYER_SYMBOL_TAG))
                .fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }

    /** The package of the next app started, waited for. */
    fun nextStarted(): String? {
        var started: Intent? = null
        compose.waitUntil(WAIT_MS) {
            started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
            started != null
        }
        return started?.`package`
    }

    /** A state written off the composition, seen at the next frames, on a paused clock. */
    fun frames() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(FRAME_MS)
    }

    /** The screen as a picture named [name] under `SLEEVESIGHT_PICTURES`, when it names a directory. */
    fun keep(name: String) {
        val dir = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } } ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val SPOTIFY = Settings.SPOTIFY
        const val YOUTUBE = "com.google.android.apps.youtube.music"
        const val CASTBOX = "fm.castbox.audiobook.radio.podcast"
        val NAMES = mapOf(SPOTIFY to "Spotify", YOUTUBE to "YouTube Music", CASTBOX to "Castbox")
        const val ALL =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        const val FRAME_MS = 32L
        const val SUNDOWN = "Sundown"

        /** A report of [title] on [player], the session layer's [play] of it. */
        fun song(
            title: String,
            player: String = SPOTIFY,
            play: Long? = null,
            artists: String = "Gordon Lightfoot",
        ) = NowPlaying(title, artists, player = player, play = play)
    }
}
