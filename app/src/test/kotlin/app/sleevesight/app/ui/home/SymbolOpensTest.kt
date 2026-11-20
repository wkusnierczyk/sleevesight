package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.CASTBOX
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.SPOTIFY
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.SymbolOpens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * *What the symbol opens* (#1445): with *The player* chosen, a tap on the note symbol opens the
 * player, the last followed or else the first switched on while nothing plays, and a long press the
 * player menu, or the panel that says nothing plays; a screen reader hears it as *Player* with its
 * actions so; and the guide's note on the player says it, one line per choice. (*Player menu*, the
 * default, is `HeaderPlayerTest`'s.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SymbolOpensTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val home =
        HeaderSymbolFixture(compose).apply {
            settings.value = settings.value?.copy(symbolOpens = SymbolOpens.PLAYER)
        }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun symbol() = compose.onNodeWithTag(PLAYER_SYMBOL_TAG)

    @Test
    fun aTapOpensThePlayerAndALongPressTheMenu() {
        home.activity = PendingIntent.getActivity(app, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
        home.show {
            symbol().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(PLAYER_MENU_OPEN_TAG).assertDoesNotExist()
            assertEquals(listOf(SPOTIFY), home.askedScreen)
            val started = shadowOf(it).nextStartedActivity ?: shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started?.action)
            symbol().performTouchInput { longClick(center) }
            compose.onNodeWithTag(PLAYER_MENU_OPEN_TAG).assertExists()
        }
    }

    @Test
    fun withNothingPlayingATapOpensTheLastPlayerFollowed() {
        home.nothingPlaying()
        home.show {
            compose.waitForIdle()
            symbol().performClick()
            compose.waitForIdle()
            assertEquals(SPOTIFY, shadowOf(app).nextStartedActivity?.`package`)
            compose.onNodeWithTag(SYMBOL_PANEL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun withNothingPlayingAndNoLastPlayerATapOpensTheFirstSwitchedOnAndALongPressThePanel() {
        home.nothingPlaying()
        home.lastPlayer.value = null
        home.show {
            // The players load off the main thread.
            compose.waitForIdle()
            symbol().performClick()
            compose.waitForIdle()
            // Castbox, Spotify, YouTube Music, A to Z: Castbox first.
            assertEquals(CASTBOX, shadowOf(app).nextStartedActivity?.`package`)
            symbol().performTouchInput { longClick(center) }
            compose.onNodeWithText(text(R.string.home_player_symbol_nothing_playing)).assertExists()
        }
    }

    @Test
    fun aScreenReaderHearsPlayerWithATapThatOpensItAndALongPressForTheMenu() =
        home.show {
            val config = symbol().fetchSemanticsNode().config
            assertEquals(listOf("Player"), config[SemanticsProperties.ContentDescription])
            assertEquals("open the player", config[SemanticsActions.OnClick].label)
            assertEquals("open a play menu to control or switch the player", config[SemanticsActions.OnLongClick].label)
        }

    @Test
    fun withPlayerMenuAScreenReaderHearsPlayerMenuWithTheActionsTheOtherWay() {
        home.settings.value = home.settings.value?.copy(symbolOpens = SymbolOpens.PLAYER_MENU)
        home.show {
            val config = symbol().fetchSemanticsNode().config
            assertEquals(listOf("Player menu"), config[SemanticsProperties.ContentDescription])
            assertEquals("open a play menu to control or switch the player", config[SemanticsActions.OnClick].label)
            assertEquals("open the player", config[SemanticsActions.OnLongClick].label)
        }
    }

    @Test
    fun theGuidesShortNoteOnThePlayerFollowsTheChoice() {
        val notes =
            listOf(SymbolOpens.PLAYER_MENU to "tap for controls", SymbolOpens.PLAYER to "tap to open the player")
        for ((opens, note) in notes) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent { SleevesightTheme { GuidePicture(Modifier.fillMaxWidth(), opens) } }
                }
                // As drawn, and as a screen reader reads its button.
                compose.onNodeWithText(note, useUnmergedTree = true).assertExists()
                val named = text(R.string.home_guide_named_note, text(R.string.home_guide_part_player), note)
                compose
                    .onNodeWithTag(guideNoteTag(GuidePart.PLAYER))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(named)))
            }
        }
    }

    @Test
    fun theGuidesNoteOnThePlayerHasALinePerChoice() {
        val menu =
            "Tap the note symbol at the top for the player menu: play, pause, skip, or switch players. " +
                "Hold it to open the player."
        val player =
            "Tap the note symbol at the top to open the player. " +
                "Hold it for the player menu: play, pause, skip, or switch players."
        for ((opens, line) in listOf(SymbolOpens.PLAYER_MENU to menu, SymbolOpens.PLAYER to player)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent { SleevesightTheme { GuidePicture(Modifier.fillMaxWidth(), opens) } }
                }
                compose.onNodeWithTag(guideNoteTag(GuidePart.PLAYER)).performClick()
                compose.onNodeWithText(line, substring = true).assertExists()
                // The sentence on the record stays after either line.
                compose.onNodeWithText("$line A tap on the record after the logo plays or pauses.").assertExists()
            }
        }
    }
}
