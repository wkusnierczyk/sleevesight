package app.sleevesight.app.ui.logo

import app.sleevesight.core.settings.LogoSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A debug build's bug crawling where the record would turn (#848), in time given, not measured: a
 * rest of 4 s, then a trip of 1.5 s at 33⅓ rpm and 1.35 times as fast at 45 rpm; a trip under way
 * when the music stops goes on to its end; and the trip's path, out of the header's top and back
 * in from its bottom, at one speed.
 */
class BugCrawlTest {
    private val lp = tripSeconds(LogoSpeed.RPM_33)
    private val single = tripSeconds(LogoSpeed.RPM_45)

    @Test
    fun aTripTakes1point5sAt33RpmAnd1point11sAt45() {
        assertEquals(1.5f, lp, EPS)
        assertEquals(1.5f / 1.35f, single, EPS)
    }

    @Test
    fun itRests4sAtEitherSpeedThenCrawlsItsTripAndRestsAgain() {
        for (trip in listOf(lp, single)) {
            assertNull(BugCrawl().after(CRAWL_REST_SECONDS - .01f, true, trip).trip)
            assertEquals(0f, checkNotNull(BugCrawl().after(CRAWL_REST_SECONDS, true, trip).trip), EPS)
            val midway = BugCrawl().after(CRAWL_REST_SECONDS + trip / 2, true, trip)
            assertEquals(.5f, checkNotNull(midway.trip), EPS)
            val back = BugCrawl().after(CRAWL_REST_SECONDS + trip + .01f, true, trip)
            assertNull(back.trip)
            assertEquals(.01f, back.rested, EPS)
        }
    }

    @Test
    fun framesAddUpAsOneStep() {
        var framed = BugCrawl()
        repeat(FRAMES) { framed = framed.after(FRAME, true, lp) }
        val whole = BugCrawl().after(FRAMES * FRAME, true, lp)
        assertEquals(checkNotNull(whole.trip), checkNotNull(framed.trip), EPS)
    }

    @Test
    fun aTripUnderWayWhenTheMusicStopsGoesOnToItsPlaceAndStaysThere() {
        val away = BugCrawl(trip = .3f)
        val on = away.after(lp / 2, false, lp)
        assertEquals(.8f, checkNotNull(on.trip), EPS)
        val home = away.after(lp, false, lp)
        assertEquals(BugCrawl(), home)
        assertEquals(BugCrawl(), home.after(10f, false, lp))
        // Resting when the music stops, it stays, and its rest starts afresh.
        assertEquals(BugCrawl(), BugCrawl(rested = 3f).after(FRAME, false, lp))
    }

    @Test
    fun aChangeOfSpeedDuringATripTakesEffectFromThereWithNoJump() {
        val away = BugCrawl(trip = .4f)
        assertEquals(.4f + .1f / single, checkNotNull(away.after(.1f, true, single).trip), EPS)
        assertEquals(.4f + .1f / lp, checkNotNull(away.after(.1f, true, lp).trip), EPS)
    }

    @Test
    fun theTripGoesOutOfTheTopAndBackFromTheBottomAtOneSpeed() {
        val above = 10f
        val below = 30f
        val half = 8f
        val out = above + half
        val total = out + below + half
        // In its place at either end, swaying not at all.
        assertEquals(0f, crawlOffset(0f, above, below, half).y, EPS)
        assertEquals(0f, crawlOffset(0f, above, below, half).x, EPS)
        assertEquals(0f, crawlOffset(1f, above, below, half).y, EPS)
        assertEquals(0f, crawlOffset(1f, above, below, half).x, .001f)
        // Its foot at the header's top, then its head at the header's bottom.
        assertEquals(-out, crawlOffset(out / total - 1e-6f, above, below, half).y, .01f)
        assertEquals(below + half, crawlOffset(out / total + 1e-6f, above, below, half).y, .01f)
        // Up all the way, by as much each step.
        val ys = (0..100).map { crawlOffset(it / 100f, above, below, half).y }
        val steps = ys.zipWithNext { a, b -> a - b }.filter { abs(it) < total / 2 }
        assertTrue(steps.all { abs(it - total / 100) < .001f })
        // A sway, small against its size.
        val xs = (0..100).map { crawlOffset(it / 100f, above, below, half).x }
        assertTrue(xs.any { it > .02f * half } && xs.all { abs(it) <= .06f * half + EPS })
    }

    private companion object {
        const val EPS = .0005f
        const val FRAME = .016f
        const val FRAMES = 300
    }
}
