package app.sleevesight.app.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.ALL
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.SPOTIFY
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.SUNDOWN
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.YOUTUBE
import app.sleevesight.app.ui.home.HeaderSymbolFixture.Companion.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The note symbol's flash of the player's logo (#1440, #1445): for 3 s when a player starts, when
 * Home switches player, and when the track changes, by a skip or the next track on its own; not on a
 * pause, a stall, or a track filled in as it plays; never with *Show the current player's logo* off;
 * whatever *Spin the logo* says; faded in and out, or swapped at once with the phone's animations
 * off; and not read by a screen reader. All on the virtual clock. The header mid-flash is kept as
 * PNGs, light and dark, when `SLEEVESIGHT_PICTURES` names a directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderLogoFlashTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val home = HeaderSymbolFixture(compose)

    private fun iconShown(shown: Boolean) {
        val icon = compose.onNodeWithTag(PLAYER_SYMBOL_ICON_TAG, useUnmergedTree = true)
        if (shown) icon.assertExists() else icon.assertDoesNotExist()
    }

    private fun noteShown(shown: Boolean) {
        val note = compose.onNodeWithTag(PLAYER_SYMBOL_NOTE_TAG, useUnmergedTree = true)
        if (shown) note.assertExists() else note.assertDoesNotExist()
    }

    // On a paused clock: [change], then the frames that see it.
    private fun paused(block: () -> Unit) =
        home.show {
            compose.mainClock.autoAdvance = false
            try {
                block()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    private fun change(write: () -> Unit) {
        write()
        home.frames()
    }

    // Past the flash and its fade out.
    private fun pastTheFlash() = compose.mainClock.advanceTimeBy(FLASH_MS + FADE_MS + MARGIN_MS)

    @Test
    fun aPlayerStartingShowsItsLogoForThreeSeconds() {
        home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, actions = ALL)
        paused {
            iconShown(false)
            change { home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
            // Still there just before the three seconds: the 1 s of #1440 is gone.
            compose.mainClock.advanceTimeBy(FLASH_MS - MARGIN_MS)
            iconShown(true)
            pastTheFlash()
            iconShown(false)
            noteShown(true)
        }
    }

    @Test
    fun homeSwitchingPlayerShowsTheNewPlayersLogo() =
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PAUSED, actions = ALL) }
            iconShown(true)
            pastTheFlash()
            iconShown(false)
        }

    @Test
    fun aSkipShowsTheLogoThroughItsPassingStates() =
        paused {
            for (status in listOf(PlaybackStatus.OTHER, PlaybackStatus.BUFFERING)) {
                change { home.playback.value = PlaybackInfo(SPOTIFY, status, actions = ALL) }
                iconShown(false)
            }
            change { home.track.value = song("Carefree Highway", play = 2) }
            change { home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
            pastTheFlash()
            iconShown(false)
        }

    @Test
    fun theNextTrackStartingOnItsOwnShowsTheLogo() =
        paused {
            change { home.track.value = song("Carefree Highway", play = 2) }
            iconShown(true)
            pastTheFlash()
            iconShown(false)
        }

    // Codex review of #1463: a stream's next song under one media id keeps the play tag
    // (`TrackIdentity.merged`); its new title is a new track all the same.
    @Test
    fun aStreamsNextSongUnderTheSamePlayTagShowsTheLogo() =
        paused {
            change { home.track.value = song("Carefree Highway", play = 1) }
            iconShown(true)
            pastTheFlash()
            iconShown(false)
            // The same song again, its artists filled in: no change.
            change { home.track.value = song("Carefree Highway", play = 1, artists = "Gordon Lightfoot, Band") }
            iconShown(false)
        }

    @Test
    fun aSkipWhilePausedShowsTheLogoToo() {
        home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, actions = ALL)
        paused {
            change { home.track.value = song("Carefree Highway", play = 2) }
            iconShown(true)
        }
    }

    @Test
    fun aPauseAStallOrATrackFilledInShowsNothing() =
        paused {
            change { home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, actions = ALL) }
            iconShown(false)
            change { home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.BUFFERING, actions = ALL) }
            iconShown(false)
            // The same play, its artists filled in.
            change { home.track.value = song(SUNDOWN, play = 1, artists = "Gordon Lightfoot, Band") }
            iconShown(false)
            // Idle for a moment, then the same play again.
            change { home.track.value = null }
            change { home.track.value = song(SUNDOWN, play = 1) }
            iconShown(false)
        }

    @Test
    fun aChangeWithinTheFlashStartsItAfresh() =
        paused {
            change { home.track.value = song("Carefree Highway", play = 2) }
            compose.mainClock.advanceTimeBy(FLASH_MS / 2)
            change { home.track.value = song("If You Could Read My Mind", play = 3) }
            compose.mainClock.advanceTimeBy(FLASH_MS - MARGIN_MS)
            iconShown(true)
            pastTheFlash()
            iconShown(false)
        }

    @Test
    fun withTheLogoSwitchedOffTheNoteStaysStill() {
        home.settings.value = home.settings.value?.copy(showPlayerLogo = false)
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(false)
            change { home.track.value = song("Carefree Highway", player = YOUTUBE, play = 2) }
            iconShown(false)
            noteShown(true)
        }
    }

    @Test
    fun theLogoSwitchedOffWithinTheFlashEndsItAndBackOnStartsNone() =
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
            change { home.settings.value = home.settings.value?.copy(showPlayerLogo = false) }
            compose.mainClock.advanceTimeBy(FADE_MS + MARGIN_MS)
            iconShown(false)
            change { home.settings.value = home.settings.value?.copy(showPlayerLogo = true) }
            compose.mainClock.advanceTimeBy(FADE_MS + MARGIN_MS)
            iconShown(false)
        }

    @Test
    fun spinTheLogoOffDoesNotStopTheFlash() {
        home.settings.value = home.settings.value?.copy(spinLogo = false)
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
        }
    }

    @Test
    fun withAnimationsOnTheLogoFadesIn() =
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            // Mid-fade: the note fading out under the logo fading in.
            iconShown(true)
            noteShown(true)
            compose.mainClock.advanceTimeBy(FADE_MS + MARGIN_MS)
            noteShown(false)
        }

    @Test
    fun withAnimationsRemovedTheLogoStillShowsSwappedWithoutAFade() {
        home.scale.floatValue = 0f
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
            noteShown(false)
            compose.mainClock.advanceTimeBy(FLASH_MS + MARGIN_MS)
            iconShown(false)
            noteShown(true)
        }
    }

    @Test
    fun theFlashIsNotRead() =
        paused {
            change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
            iconShown(true)
            val symbol = compose.onNodeWithTag(PLAYER_SYMBOL_TAG).fetchSemanticsNode().config
            val name = listOf(app.getString(R.string.home_player_symbol))
            assertEquals(name, symbol[SemanticsProperties.ContentDescription])
            assertFalse(SemanticsProperties.LiveRegion in symbol)
            val icon = compose.onNodeWithTag(PLAYER_SYMBOL_ICON_TAG, useUnmergedTree = true).fetchSemanticsNode().config
            assertFalse(SemanticsProperties.LiveRegion in icon)
            assertFalse(SemanticsProperties.ContentDescription in icon)
        }

    @Test
    fun headerMidFlashPictures() {
        // Swapped at once, so the picture shows the logo whole.
        home.scale.floatValue = 0f
        for (dark in listOf(false, true)) {
            home.playback.value = PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, actions = ALL)
            home.show(dark) {
                compose.mainClock.autoAdvance = false
                try {
                    change { home.playback.value = PlaybackInfo(YOUTUBE, PlaybackStatus.PLAYING, actions = ALL) }
                    iconShown(true)
                    home.keep("header-flash-${if (dark) "dark" else "light"}")
                } finally {
                    compose.mainClock.autoAdvance = true
                }
            }
        }
    }

    private companion object {
        const val MARGIN_MS = 100L
    }
}
