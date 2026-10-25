package app.sleevesight.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's name as a computer receives it in the scan response (#433): Android's 26 bytes. */
class AdvertisedNameTest {
    @Test
    fun aNameThatFitsIsWhole() {
        assertEquals("Galaxy S25 Ultra", advertisedName("Galaxy S25 Ultra"))
        val exactly = "a".repeat(BleKeyboard.NAME_MAX_BYTES)
        assertEquals(exactly, advertisedName(exactly))
    }

    @Test
    fun aLongerNameIsShortenedToItsFirst26Bytes() {
        assertEquals("Living room Pixel 9 Pro Fo", advertisedName("Living room Pixel 9 Pro Fold"))
    }

    @Test
    fun aCharacterCutByTheLimitIsLeftOut() {
        // 25 ASCII bytes, then "ł" (2 bytes): the limit falls in its middle.
        val name = "a".repeat(25) + "łb"
        assertEquals("a".repeat(25), advertisedName(name))
        // An emoji of 4 bytes after 24: also cut, and left out whole.
        assertEquals("b".repeat(24), advertisedName("b".repeat(24) + "🎵"))
    }
}
