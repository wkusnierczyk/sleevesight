package app.sleevesight.core.settings

import app.sleevesight.core.settings.WriteAutomatically.NEVER
import app.sleevesight.core.settings.WriteAutomatically.ON_EVERY_TRACK_CHANGE
import app.sleevesight.core.settings.WriteAutomatically.WHEN_I_SKIP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The insight card's toggle for automatic writing (#553) as the settings map it: one setting with
 * *Reactive*; off is *Manual*, on restores the automatic mode used last, *On track change* if none.
 */
class AutomaticWritingTest {
    @Test
    fun `the toggle is on under either automatic mode and off under Manual`() {
        assertTrue(Settings(writeAutomatically = ON_EVERY_TRACK_CHANGE).writesAutomatically)
        assertTrue(Settings(writeAutomatically = WHEN_I_SKIP).writesAutomatically)
        assertFalse(Settings(writeAutomatically = NEVER).writesAutomatically)
    }

    @Test
    fun `turning it off sets Reactive to Manual`() {
        assertEquals(NEVER, Settings().withAutomaticWriting(false).writeAutomatically)
        assertEquals(NEVER, Settings(writeAutomatically = WHEN_I_SKIP).withAutomaticWriting(false).writeAutomatically)
    }

    @Test
    fun `turning it on again restores the mode used before`() {
        val skip = Settings().withWriteAutomatically(WHEN_I_SKIP)
        val off = skip.withAutomaticWriting(false)
        assertEquals(WHEN_I_SKIP, off.lastAutomaticMode)
        assertEquals(WHEN_I_SKIP, off.withAutomaticWriting(true).writeAutomatically)
    }

    @Test
    fun `a mode stored with no last mode, as before the toggle, is the one restored`() {
        // An install from before #553: On skip stored, no last automatic mode, which reads as the default.
        val upgraded = Settings(writeAutomatically = WHEN_I_SKIP)
        assertEquals(WHEN_I_SKIP, upgraded.withAutomaticWriting(false).withAutomaticWriting(true).writeAutomatically)
    }

    @Test
    fun `turning it on with no automatic mode ever used is On track change`() {
        val manual = Settings(writeAutomatically = NEVER)
        assertEquals(ON_EVERY_TRACK_CHANGE, manual.withAutomaticWriting(true).writeAutomatically)
    }

    @Test
    fun `turning it on while already on keeps the mode`() {
        val skip = Settings(writeAutomatically = WHEN_I_SKIP)
        assertEquals(skip, skip.withAutomaticWriting(true))
    }

    @Test
    fun `Reactive changed in the settings is what the toggle restores`() {
        // Manual chosen under Reactive, from On skip, then the toggle turned on: On skip again.
        val manual = Settings().withWriteAutomatically(WHEN_I_SKIP).withWriteAutomatically(NEVER)
        assertEquals(WHEN_I_SKIP, manual.lastAutomaticMode)
        assertEquals(WHEN_I_SKIP, manual.withAutomaticWriting(true).writeAutomatically)
        // Manual chosen again from Manual keeps it.
        assertEquals(WHEN_I_SKIP, manual.withWriteAutomatically(NEVER).lastAutomaticMode)
    }

    @Test
    fun `the last automatic mode is never Manual`() {
        assertFailsWith<IllegalArgumentException> { Settings(lastAutomaticMode = NEVER) }
        assertEquals(ON_EVERY_TRACK_CHANGE, Settings().lastAutomaticMode)
    }
}
