package app.sleevesight.app.session

import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.pipeline.ChangeCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

/**
 * The controls Home's record and player menu send (#1409, #1209), and the player chosen in the menu:
 * only what the shown session advertises goes to it; a change after a Previous or Next sent here, or
 * after a player chosen, is the user's action, not judged by skip detection (D5); the choice holds
 * while its player has a session and no other starts to play.
 */
@RunWith(RobolectricTestRunner::class)
internal class SessionControlsTest : SessionRepositoryFixture() {
    private val all =
        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    @Test
    fun theStateCarriesTheActionsAndOnlyThoseAdvertisedAreSent() {
        val spotify = FakeSession(SPOTIFY, playbackState = state(actions = PlaybackState.ACTION_PAUSE))
        system.active = listOf(spotify)
        repository.start()
        settle()
        assertEquals(PlaybackState.ACTION_PAUSE, repository.playback.value.actions)
        assertEquals(Transport.PAUSE, repository.playback.value.toggle)

        repository.send(Transport.NEXT)
        repository.send(Transport.PLAY)
        repository.send(Transport.PAUSE)
        assertEquals(listOf(Transport.PAUSE), spotify.sent)
    }

    @Test
    fun theCombinedPlayPauseActionAllowsBoth() {
        val info = PlaybackInfo(SPOTIFY, PlaybackStatus.PAUSED, actions = PlaybackState.ACTION_PLAY_PAUSE)
        assertTrue(info.allows(Transport.PLAY) && info.allows(Transport.PAUSE))
        assertEquals(Transport.PLAY, info.toggle)
        assertEquals(false, info.allows(Transport.NEXT))
        assertEquals(false, PlaybackInfo.NONE.copy(actions = all).allows(Transport.PLAY))
    }

    @Test
    fun aPlayerAdvertisingNothingIsSentNothing() {
        val spotify = FakeSession(SPOTIFY, playbackState = state())
        system.active = listOf(spotify)
        repository.start()
        settle()
        Transport.entries.forEach(repository::send)
        assertEquals(emptyList<Transport>(), spotify.sent)
    }

    @Test
    fun aNextSentHereMakesTheChangeTheUsersWithoutDetection() {
        val spotify =
            FakeSession(SPOTIFY, metadata = metadata(durationMs = 200_000), playbackState = state(actions = all))
        system.active = listOf(spotify)
        repository.start()
        settle()
        // At a place a change would be read as a natural end: near the track's end.
        at(5_000)
        spotify.send(state(positionMs = 199_990, updatedAt = 5_000, actions = all))
        repository.send(Transport.NEXT)
        assertEquals(listOf(Transport.NEXT), spotify.sent)
        at(5_100)
        spotify.send(metadata(title = "Carefree Highway", durationMs = 200_000))
        settle()
        assertEquals(ChangeCause.USER_ACTION, repository.lastChange.value?.cause)
        assertEquals(ChangeCause.USER_ACTION, repository.trackChanges.value?.cause)

        // The next change, by itself, is judged again.
        at(60_000)
        spotify.send(state(positionMs = 199_990, updatedAt = 60_000, actions = all))
        at(60_100)
        spotify.send(metadata(title = "Sundown", durationMs = 200_000))
        settle()
        assertEquals(ChangeCause.NATURAL_END, repository.lastChange.value?.cause)
    }

    @Test
    fun aChangeLongAfterTheNextIsNotTakenForIt() {
        val spotify =
            FakeSession(SPOTIFY, metadata = metadata(durationMs = 200_000), playbackState = state(actions = all))
        system.active = listOf(spotify)
        repository.start()
        settle()
        at(1_000)
        repository.send(Transport.NEXT)
        // The player ignored it; the track ends by itself much later.
        at(100_000)
        spotify.send(state(positionMs = 199_990, updatedAt = 100_000, actions = all))
        at(100_100)
        spotify.send(metadata(title = "Carefree Highway", durationMs = 200_000))
        settle()
        assertEquals(ChangeCause.NATURAL_END, repository.lastChange.value?.cause)
    }

    @Test
    fun theFollowedPlayersAreListedWithTheirStates() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, playbackState = state())
        val youtube = FakeSession(YOUTUBE_MUSIC, playbackState = state(PlaybackState.STATE_PAUSED))
        system.active = listOf(spotify, youtube)
        repository.start()
        settle()
        assertEquals(
            mapOf(SPOTIFY to PlaybackStatus.PLAYING, YOUTUBE_MUSIC to PlaybackStatus.PAUSED),
            repository.followedStates.value,
        )
    }

    @Test
    fun aPlayerChosenIsShownUntilAnotherStartsToPlay() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, playbackState = state())
        val youtube =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = carefree,
                playbackState = state(PlaybackState.STATE_PAUSED, actions = all),
            )
        system.active = listOf(spotify, youtube)
        repository.start()
        settle()
        assertEquals(SPOTIFY, repository.playback.value.player)

        repository.follow(YOUTUBE_MUSIC)
        settle()
        // Shown though paused while Spotify plays on, the switch the user's action.
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)
        assertEquals("Carefree Highway", repository.nowPlaying.value?.title)
        assertEquals(ChangeCause.USER_ACTION, repository.lastChange.value?.cause)
        // Its controls go to it.
        repository.send(Transport.PLAY)
        assertEquals(listOf(Transport.PLAY), youtube.sent)
        assertEquals(emptyList<Transport>(), spotify.sent)

        // Spotify's reports, while it plays on, keep the choice.
        spotify.settled(state(positionMs = 1_000))
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)

        // Spotify stops, then starts again: the choice ends.
        spotify.settled(state(PlaybackState.STATE_PAUSED))
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)
        spotify.settled(state())
        assertEquals(SPOTIFY, repository.playback.value.player)
    }

    @Test
    fun aChosenPlayerWhoseSessionEndsLeavesTheChoice() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, playbackState = state())
        val youtube = FakeSession(YOUTUBE_MUSIC, metadata = carefree, playbackState = state(PlaybackState.STATE_PAUSED))
        system.active = listOf(spotify, youtube)
        repository.start()
        settle()
        repository.follow(YOUTUBE_MUSIC)
        settle()
        assertEquals(YOUTUBE_MUSIC, repository.playback.value.player)
        system.change(listOf(spotify))
        settle()
        assertEquals(SPOTIFY, repository.playback.value.player)
        // Back, it is not shown again by the old choice.
        system.change(listOf(spotify, youtube))
        settle()
        assertEquals(SPOTIFY, repository.playback.value.player)
    }

    @Test
    fun aPlayerWithNoSessionCannotBeChosen() {
        followed.value = setOf(SPOTIFY, YOUTUBE_MUSIC)
        val spotify = FakeSession(SPOTIFY, playbackState = state())
        system.active = listOf(spotify)
        repository.start()
        settle()
        repository.follow(YOUTUBE_MUSIC)
        settle()
        assertEquals(SPOTIFY, repository.playback.value.player)
    }
}
