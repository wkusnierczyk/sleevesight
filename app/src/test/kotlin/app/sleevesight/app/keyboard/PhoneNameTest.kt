package app.sleevesight.app.keyboard

import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's name in the scan response (#433): which start carries it, the fallback to none, and
 * the name as the computer's chooser shows it, against the 26 bytes Android sends.
 */
class PhoneNameTest {
    // The bytes Android's stack sends for [name]: its first 26 bytes of UTF-8 (`AdvertiseHelper`).
    private fun sent(name: String): ByteArray = name.toByteArray(Charsets.UTF_8).take(PhoneName.MAX_BYTES).toByteArray()

    // The name shown, without the "…" of a shortened one, is the start of what Android sends.
    private fun assertStartsWhatIsSent(name: String) {
        val shown = advertisedName(name).removeSuffix("…").toByteArray(Charsets.UTF_8)
        assertTrue(sent(name).take(shown.size) == shown.toList())
    }

    @Test
    fun aNameThatFitsIsWhole() {
        assertEquals("Galaxy S25 Ultra", advertisedName("Galaxy S25 Ultra"))
        val exactly = "a".repeat(PhoneName.MAX_BYTES)
        assertEquals(exactly, advertisedName(exactly))
    }

    @Test
    fun aLongerNameIsItsFirst26BytesMarkedShortened() {
        val name = "Living room Pixel 9 Pro Fold"
        assertEquals("Living room Pixel 9 Pro Fo…", advertisedName(name))
        assertStartsWhatIsSent(name)
    }

    @Test
    fun aCharacterCutByTheLimitIsLeftOut() {
        // 25 ASCII bytes, then "ł" (2 bytes): Android sends its first byte alone.
        val split = "a".repeat(25) + "łb"
        assertEquals("a".repeat(25) + "…", advertisedName(split))
        assertStartsWhatIsSent(split)
        // An emoji of 4 bytes after 24: also cut, and left out whole.
        val emoji = "b".repeat(24) + "🎵"
        assertEquals("b".repeat(24) + "…", advertisedName(emoji))
        assertStartsWhatIsSent(emoji)
    }

    @Test
    fun aCharacterStartingJustPastTheLimitIsLeftOutWhole() {
        // The limit falls on the lead byte of "ł": the 26 bytes before it are whole characters.
        val name = "a".repeat(26) + "ł"
        assertEquals("a".repeat(26) + "…", advertisedName(name))
        assertStartsWhatIsSent(name)
    }

    @Test
    fun offNoStartCarriesTheName() {
        val name = PhoneName(show = false) { "Pixel 9" }
        assertNull(name.next())
        assertFalse(name.retryNameless(null, ADVERTISE_FAILED_INTERNAL_ERROR))
    }

    @Test
    fun theNameIsReadAtEachStartAndBlankIsNone() {
        var current: String? = "Pixel 9"
        val name = PhoneName(show = true) { current }
        assertEquals("Pixel 9", name.next())
        current = "Kitchen phone"
        assertEquals("Kitchen phone", name.next())
        current = "  "
        assertNull(name.next())
        current = null
        assertNull(name.next())
    }

    @Test
    fun aStartWithTheNameThatFailsIsMadeAgainWithoutIt() {
        for (error in listOf(ADVERTISE_FAILED_DATA_TOO_LARGE, ADVERTISE_FAILED_INTERNAL_ERROR)) {
            val name = PhoneName(show = true) { "Pixel 9" }
            assertTrue("error $error", name.retryNameless(name.next(), error))
            // No later start carries it, and a nameless start that fails is not retried as such.
            assertNull(name.next())
            assertFalse(name.retryNameless(null, error))
        }
    }

    @Test
    fun alreadyStartedIsNoFailure() {
        val name = PhoneName(show = true) { "Pixel 9" }
        assertFalse(name.retryNameless(name.next(), ADVERTISE_FAILED_ALREADY_STARTED))
        assertEquals("Pixel 9", name.next())
    }
}
