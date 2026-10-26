package app.sleevesight.core.settings

import app.sleevesight.core.settings.WriteAutomatically.NEVER
import app.sleevesight.core.settings.WriteAutomatically.ON_EVERY_TRACK_CHANGE
import app.sleevesight.core.settings.WriteAutomatically.WHEN_I_SKIP
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * *Reactive*'s modes as automatic writing reads them: *On track change* and *On track skip* write
 * without a tap, *Now* does not.
 */
class AutomaticWritingTest {
    @Test
    fun `either automatic mode writes automatically and Now does not`() {
        assertTrue(Settings(writeAutomatically = ON_EVERY_TRACK_CHANGE).writesAutomatically)
        assertTrue(Settings(writeAutomatically = WHEN_I_SKIP).writesAutomatically)
        assertFalse(Settings(writeAutomatically = NEVER).writesAutomatically)
    }
}
