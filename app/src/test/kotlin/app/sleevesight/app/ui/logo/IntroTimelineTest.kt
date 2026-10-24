package app.sleevesight.app.ui.logo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The intros' timelines (#383), as the product page's (`index.html`, `.sl-intro`). */
class IntroTimelineTest {
    private fun near(
        what: String,
        expected: Float,
        actual: Float,
        within: Float = .01f,
    ) = assertTrue("$what: $expected, was $actual", abs(expected - actual) <= within)

    private fun near(
        what: String,
        expected: Offset,
        actual: Offset,
    ) {
        near("$what x", expected.x, actual.x)
        near("$what y", expected.y, actual.y)
    }

    @Test
    fun eachIntroStartsUnseenOrOffItsPlace() {
        for (variant in listOf(IntroVariant.C, IntroVariant.D, IntroVariant.F, IntroVariant.R1, IntroVariant.R2)) {
            assertEquals("$variant fades in", 0f, splashPose(variant, 0f).alpha)
        }
        // R1's record waits off to the left, unseen, and "sight" is hidden left of it.
        val r1 = splashPose(IntroVariant.R1, 500f)
        assertEquals(0f, r1.record!!.alpha)
        near("R1's record", RecordCentre.x - 248.53f, r1.record!!.centre.x)
        assertTrue("R1's sight hidden", r1.sightClip!! < -16.9f)
        // R2's tile covers the large record, to its right.
        val r2 = splashPose(IntroVariant.R2, 400f)
        near("R2's tile", Offset(140f - 39.5f, 0f), r2.tile!!)
        near("R2's record", Offset(140f - 39.5f, 0f), r2.record!!.centre)
        assertTrue(r2.recordUnder)
    }

    @Test
    fun eachIntroIsTheStackedMarkWhenItsFlightStarts() {
        for (variant in IntroVariant.entries) {
            val pose = splashPose(variant, variant.fly.toFloat())
            assertEquals("$variant shown", 1f, pose.alpha)
            near("$variant turned whole turns", 0f, pose.degrees % 360f)
            near("$variant Sleeve", Offset.Zero, pose.sleeveAt)
            near("$variant sight", Offset.Zero, pose.sightAt)
            pose.sightClip?.let { assertTrue("$variant shows all of sight", it >= 45.52f) }
            val record = pose.record
            if (variant == IntroVariant.D) {
                assertNull("D's record turns in on the header", record)
            } else {
                near("$variant record", RecordCentre, record!!.centre)
                near("$variant record's size", LogoOutlines.RECORD_R, record.radius)
                near("$variant record turned whole turns", 0f, record.degrees % 360f)
                assertEquals("$variant record shown", 1f, record.alpha)
            }
        }
        assertEquals("R2's tile gone", 0f, splashPose(IntroVariant.R2, IntroVariant.R2.fly.toFloat()).tileAlpha)
    }

    @Test
    fun theTurnsAndTheRollAreThePages() {
        // Halfway through C's turning, by its symmetric easing, one of its two turns.
        near("C halfway", 360f, splashPose(IntroVariant.C, 1900f).degrees, within = .5f)
        assertEquals(RecordCentre, splashPose(IntroVariant.C, 1900f).pivot)
        assertEquals(Offset.Zero, splashPose(IntroVariant.D, 1900f).pivot)
        // F's record halfway round the dot of the i: opposite where it started.
        near("F halfway", -RecordCentre, splashPose(IntroVariant.F, 1900f).record!!.centre)
        // R1 rolls two and a quarter turns over its circumference.
        near("R1's roll", 2.25f * 2 * Math.PI.toFloat() * LogoOutlines.RECORD_R, 248.53f, within = .05f)
        near("R1's turn", -810f, splashPose(IntroVariant.R1, 700f).record!!.degrees)
    }

    @Test
    fun theIntrosEndOnceTheScreenHasArrived() {
        assertEquals(3950L + 450, IntroVariant.C.end(toHeader = true))
        assertEquals(4750L + 450, IntroVariant.R2.end(toHeader = false))
        // D's record turns in on the header after the landing; with no header, there is none.
        assertEquals(3950L + 250 + 700, IntroVariant.D.end(toHeader = true))
        assertEquals(3950L + 450, IntroVariant.D.end(toHeader = false))
    }

    @Test
    fun theFlightPutsEachPartWhereTheHeaderLogoDrawsIt() {
        // A header logo 48 px tall at (100, 40): the one-line logo's box, from the S to the record.
        val target = Rect(Offset(100f, 40f), Size(48f * LineBox.width / LineBox.height, 48f))
        val sleeve = headerPlace(target, Offset.Zero)
        val sight = headerPlace(target, LineShift)

        fun Place.of(p: Offset) = at + p * scale
        // The S's left and the l's top at the box's top left.
        near("S", target.topLeft, sleeve.of(Offset(LineBox.LEFT, LineBox.TOP)))
        // The record's right and foot at its bottom right.
        val foot = RecordCentre + Offset(LogoOutlines.RECORD_R, LogoOutlines.RECORD_R)
        near("record", target.bottomRight, sight.of(foot))
        // "sight" on Sleeve's line: its baseline (23.82 in the mark) level with Sleeve's (-10.02).
        near("baseline", sleeve.of(Offset(0f, -10.02f)).y, sight.of(Offset(0f, 23.82f)).y)
    }

    @Test
    fun theOneLineLogoFillsItsHeightAndShrinksToFitANarrowBox() {
        // Wide enough: as tall as the box.
        val wide = linePlace(Size(400f, 40f))
        near("full height", 40f / LineBox.height, wide.scale)
        // Narrower than the logo at that height: as wide as the box, centred in its height.
        val box = Size(100f, 40f)
        assertTrue(box.width < box.height * LineBox.width / LineBox.height)
        val narrow = linePlace(box)
        near("fits the width", box.width / LineBox.width, narrow.scale)

        fun Place.of(p: Offset) = at + p * scale
        val topLeft = narrow.of(Offset(LineBox.LEFT, LineBox.TOP))
        val bottomRight = narrow.of(Offset(LineBox.right, LineBox.bottom))
        near("from the left", 0f, topLeft.x)
        near("to the right", box.width, bottomRight.x)
        near("centred", box.height - bottomRight.y, topLeft.y)
        assertTrue("inside: $topLeft, $bottomRight", topLeft.y >= 0f && bottomRight.y <= box.height)
    }

    @Test
    fun theOneLineLogoReadsAsOneWord() {
        // "sight"'s s (from -16.9) starts just after Sleeve's last e (to 44.99), no further apart
        // than letters within a word, and the record's top is the h's, its foot the g's.
        val gap = -16.9f + LineShift.x - 44.99f
        assertTrue("gap $gap", gap in 0f..4f)
        near("one line", -33.84f, LineShift.y)
        near("record's top is the l's", LineBox.TOP, LogoOutlines.RECORD_Y - LogoOutlines.RECORD_R + LineShift.y)
    }
}
