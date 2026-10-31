package app.sleevesight.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.idleUntil
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.InstalledPlayers
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * *Players* (#535): each player the phone shows, or that reported music, listed with its app's
 * icon at the row's start once it has loaded, off the main thread; one neither shown nor seen,
 * Spotify included, is hidden, and no entry is deleted for it.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerIconsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var launch: (String) -> Intent? = { null }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    /**
     * The phone showing this app only [visible], each with a plain icon given by [icon]; [seen]
     * reported music since the process started.
     */
    private fun phone(
        visible: Collection<String>,
        seen: Set<String> = emptySet(),
        icon: (String) -> Drawable? = { ColorDrawable(Color.RED) },
    ) = InstalledPlayers(
        icon = { if (it in visible) icon(it) else null },
        visible = { it in visible },
        seen = MutableStateFlow(seen),
    )

    private fun stored(vararg players: Pair<String, Boolean>) =
        runBlocking { settings.update { it.copy(followedPlayers = it.followedPlayers + players) } }

    private fun showPlayers(
        installed: InstalledPlayers,
        label: (String) -> String = NAMES::getValue,
        test: (ActivityScenario<MainActivity>) -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        installedPlayers = installed,
                        playerLaunch = launch,
                        playerLabel = label,
                    )
                }
            }
            compose.openSection(SettingsSection.PLAYERS)
            test(scenario)
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun row(name: String) = compose.onNode(isToggleable() and hasText(name))

    /** How far [name] starts from its row's start: 0 without an icon, 56 dp with one. */
    private fun indent(name: String): Dp =
        compose.onNodeWithText(name, useUnmergedTree = true).getBoundsInRoot().left - row(name).getBoundsInRoot().left

    @Test
    fun aShownPlayerHasItsIconAndOneNeitherShownNorSeenIsHiddenWithItsEntryKept() {
        stored(VISIBLE to false, HIDDEN to true)
        showPlayers(phone(visible = setOf(VISIBLE))) {
            compose.waitUntil(WAIT_MS) { shown("Example") }
            assertTrue("Spotify, not shown, is not listed", !shown("Spotify"))
            assertTrue("a player neither shown nor seen is not listed", !shown("Hidden"))

            // The icon is at the row's start, before the name: 40 dp and a 16 dp gap.
            compose.waitUntil(WAIT_MS) { indent("Example") >= 56.dp }
            // And is decorative: the row is read by its name alone, as before.
            row("Example").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            assertEquals(
                listOf("Example"),
                row("Example").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
            )

            // Not being shown deletes nothing: Spotify's and the hidden player's entries stay.
            compose.waitForIdle()
            assertEquals(
                mapOf(Settings.SPOTIFY to true, VISIBLE to false, HIDDEN to true),
                settings.now().followedPlayers,
            )
        }
    }

    @Test
    fun aPlayerSeenButNotShownIsListedByNameAndCanBeSwitchedOnAndOff() {
        stored(HIDDEN to false)
        showPlayers(phone(visible = emptySet(), seen = setOf(HIDDEN))) {
            compose.waitUntil(WAIT_MS) { shown("Hidden") }
            // No icon to give: the name starts the row.
            assertTrue(indent("Hidden") < 16.dp)

            row("Hidden").performClick()
            idleUntil("switched on") { settings.now().followedPlayers[HIDDEN] == true }
            row("Hidden").performClick()
            idleUntil("switched off") { settings.now().followedPlayers[HIDDEN] == false }
        }
    }

    @Test
    fun aRowShowsAtOnceAndItsIconLoadsOffTheMainThread() {
        stored(VISIBLE to false)
        val release = CountDownLatch(1)
        val loadedOn = AtomicReference<Thread>()
        val slow = { _: String ->
            loadedOn.set(Thread.currentThread())
            check(release.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "the icon was never released" }
            ColorDrawable(Color.RED)
        }
        try {
            showPlayers(phone(visible = setOf(VISIBLE), icon = slow)) {
                compose.waitUntil(WAIT_MS) { shown("Example") }
                assertTrue("no icon yet", indent("Example") < 16.dp)
                release.countDown()
                compose.waitUntil(WAIT_MS) { indent("Example") >= 56.dp }
                assertNotSame(Looper.getMainLooper().thread, loadedOn.get())
            }
        } finally {
            release.countDown()
        }
    }

    /** #579: a player's name and the intent that opens it are asked off the main thread too. */
    @Test
    fun aRowsNameAndLinkAreAskedOffTheMainThread() {
        stored(VISIBLE to false)
        val asked = ConcurrentHashMap<String, Thread>()
        launch = {
            asked["launch"] = Thread.currentThread()
            null
        }
        val label = { player: String ->
            asked["label"] = Thread.currentThread()
            NAMES.getValue(player)
        }
        showPlayers(phone(visible = setOf(VISIBLE)), label) {
            compose.waitUntil(WAIT_MS) { shown("Example") }
            assertEquals(setOf("label", "launch"), asked.keys)
            assertTrue(asked.values.none { it == Looper.getMainLooper().thread })
        }
    }

    /** #579: a player installed while *Players* is open shows once the screen resumes. */
    @Test
    fun aPlayerInstalledWhileTheSectionIsOpenShowsOnResume() {
        stored(VISIBLE to false)
        val visible = CopyOnWriteArraySet<String>()
        showPlayers(phone(visible = visible)) { scenario ->
            compose.waitUntil(WAIT_MS) { shown(app.getString(R.string.settings_players_none)) }
            assertTrue(!shown("Example"))

            visible += VISIBLE
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(WAIT_MS) { shown("Example") }
        }
    }

    @Test
    fun withNoPlayerShownTheSectionSaysHowOneIsAdded() {
        showPlayers(phone(visible = emptySet())) {
            val none = app.getString(R.string.settings_players_none)
            compose.waitUntil(WAIT_MS) { shown(none) }
            assertTrue(!shown("Spotify"))
            // Spotify's entry is kept, still on, for when it is installed.
            assertEquals(mapOf(Settings.SPOTIFY to true), settings.now().followedPlayers)
        }
    }

    private companion object {
        const val VISIBLE = "com.example.player"
        const val HIDDEN = "com.example.hidden"
        val NAMES = mapOf(Settings.SPOTIFY to "Spotify", VISIBLE to "Example", HIDDEN to "Hidden")
    }
}
