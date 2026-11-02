package app.sleevesight.app.ui.logo

import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header logo's record's motion (#848), in time given, not measured: up to speed in 1 s and to
 * rest in 2 s, as a turntable, resting where it stops and starting again from there; and when it
 * turns: while the player line says *Playing* and the setting is on, at the setting's speed.
 */
class LogoSpinTest {
    private val lp = LogoSpeed.RPM_33.rpm * DEGREES_PER_RPM
    private val single = LogoSpeed.RPM_45.rpm * DEGREES_PER_RPM

    @Test
    fun anLpTurnsOnceIn1point8sAndASingleIn1point33s() {
        assertEquals(1.8f, FULL_TURN / lp, EPS)
        assertEquals(4f / 3, FULL_TURN / single, EPS)
    }

    @Test
    fun fromRestItIsUpToSpeedInASecondHavingTurnedHalfAsFarAsAtFullSpeed() {
        val half = RecordMotion().after(.5f, lp, lp)
        assertEquals(lp / 2, half.speed, EPS)
        val up = RecordMotion().after(1f, lp, lp)
        assertEquals(lp, up.speed, EPS)
        assertEquals(lp / 2, up.degrees, EPS)
    }

    @Test
    fun fromFullSpeedItComesToRestInTwoSecondsAndStaysWhereItStops() {
        val start = RecordMotion(degrees = 10f, speed = lp)
        val midway = start.after(1f, 0f, lp)
        assertEquals(lp / 2, midway.speed, EPS)
        val rest = start.after(2f, 0f, lp)
        assertEquals(0f, rest.speed, EPS)
        // Its mean speed over the 2 s, half its full speed: a full speed's second, 200 degrees.
        assertEquals(10f + lp, rest.degrees, EPS)
        // At rest it does not move, nor snap back upright.
        assertEquals(rest, rest.after(5f, 0f, lp))
    }

    @Test
    fun itStartsAgainFromWhereItRested() {
        val again = RecordMotion(degrees = 123f).after(.5f, lp, lp)
        // Half a second getting up to half its speed: an eighth of a second's full-speed turn.
        assertEquals(123f + lp / 8, again.degrees, EPS)
    }

    @Test
    fun theTurnIsTheSameHoweverTheTimeIsCutIntoFrames() {
        // Up, a while at speed, and down to rest, in one step each and in frames of 16 ms.
        val whole = RecordMotion().after(FRAMES * FRAME, lp, lp).after(FRAMES * FRAME, 0f, lp)
        var framed = RecordMotion()
        repeat(FRAMES) { framed = framed.after(FRAME, lp, lp) }
        repeat(FRAMES) { framed = framed.after(FRAME, 0f, lp) }
        assertEquals(whole.speed, framed.speed, EPS)
        assertEquals(whole.degrees, framed.degrees, .05f)
    }

    @Test
    fun itTurnsClockwiseWithinOneTurn() {
        var motion = RecordMotion()
        repeat(FRAMES) {
            motion = motion.after(FRAME, single, single)
            assertTrue("${motion.degrees}", motion.degrees in 0f..FULL_TURN)
        }
    }

    @Test
    fun aChangeOfSpeedWhileTurningGoesToTheNewSpeedAtTheSameRates() {
        // 45 to 33⅓ rpm: down at the stopping rate, the LP's full speed in 2 s.
        val down = RecordMotion(speed = single).after(.1f, lp, lp)
        assertEquals(single - lp / STOP_SECONDS * .1f, down.speed, EPS)
        // 33⅓ to 45 rpm: up at the starting rate, the single's full speed in 1 s.
        val up = RecordMotion(speed = lp).after(.1f, single, single)
        assertEquals(lp + single / START_SECONDS * .1f, up.speed, EPS)
    }

    private val track = NowPlaying("Already Forgiven", "Basia Bulat", "Are You in Love?", player = PLAYER)

    @Test
    fun itTurnsWhileThePlayerLineSaysPlayingAndTheSettingIsOn() {
        val playing = PlaybackInfo(PLAYER, PlaybackStatus.PLAYING)
        assertEquals(LogoSpin(true, LogoSpeed.RPM_33), LogoSpin.of(track, playing, Settings()))
        assertEquals(
            LogoSpin(true, LogoSpeed.RPM_45),
            LogoSpin.of(track, playing, Settings(logoSpeed = LogoSpeed.RPM_45)),
        )
        // Before the settings' first read, still: the setting may be off.
        assertEquals(LogoSpin.STILL, LogoSpin.of(track, playing, null))
    }

    @Test
    fun itStandsStillWhenTheLineSaysAnythingElseOrTheSettingIsOff() {
        val playing = PlaybackInfo(PLAYER, PlaybackStatus.PLAYING)
        assertFalse(LogoSpin.of(track, playing, Settings(spinLogo = false)).turning)
        for (status in PlaybackStatus.entries - PlaybackStatus.PLAYING) {
            assertFalse("$status", LogoSpin.of(track, PlaybackInfo(PLAYER, status), Settings()).turning)
        }
        // Another player's playing, which the line does not show; and no track.
        assertFalse(LogoSpin.of(track, PlaybackInfo("other.player", PlaybackStatus.PLAYING), Settings()).turning)
        assertFalse(LogoSpin.of(null, playing, Settings()).turning)
    }

    private companion object {
        const val PLAYER = "com.spotify.music"
        const val FULL_TURN = 360f
        const val EPS = .001f
        const val FRAME = .016f
        const val FRAMES = 3000 / 16 + 1 // Past 3 s.
    }
}
