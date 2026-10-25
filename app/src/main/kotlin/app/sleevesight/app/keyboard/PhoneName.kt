package app.sleevesight.app.keyboard

import android.bluetooth.le.AdvertiseCallback

/**
 * Whether the phone's Bluetooth name goes in a [BleKeyboard]'s scan response (#433), start by start,
 * for one opening of its service: with [show] (*Show this phone's name to the computer*), the name
 * as [read] gives it at each start, so a name changed meanwhile is the one advertised; a blank or
 * unreadable name is none.
 *
 * A start that carried the name and failed is tried again without it, for the rest of the opening:
 * Android refuses a name too long for the scan response (`ADVERTISE_FAILED_DATA_TOO_LARGE`), and a
 * vendor's stack may refuse a scan response with another error, and the name, an opt-in that only
 * confirms which device is the phone, must not fail the session. Not for
 * `ADVERTISE_FAILED_ALREADY_STARTED`, which is no failure.
 */
internal class PhoneName(
    show: Boolean,
    private val read: () -> String?,
) {
    private var named = show

    /** The name for the next start's scan response, or null for none. */
    fun next(): String? = if (named) read()?.takeIf { it.isNotBlank() } else null

    /**
     * Whether a start that carried [shown] (null: none) and failed with [errorCode] is to be tried
     * again without the name; if so, no later start carries it.
     */
    fun retryNameless(
        shown: String?,
        errorCode: Int,
    ): Boolean {
        val retry = shown != null && errorCode != AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED
        if (retry) named = false
        return retry
    }

    companion object {
        /** The most of the phone's name, in UTF-8 bytes, that Android puts in an advertisement. */
        const val MAX_BYTES = 26
    }
}

/**
 * The phone's Bluetooth [name] as the computer's chooser shows it: whole, or, past
 * [PhoneName.MAX_BYTES] bytes of UTF-8, shortened, followed by "…". Android's stack sends exactly the
 * first 26 bytes (`AdvertiseHelper`, a *Shortened Local Name*), a character cut there included, which
 * the computer may show as a stray mark; what is given here is the whole characters within those
 * bytes, so the chooser's line starts with it.
 */
internal fun advertisedName(name: String): String {
    val bytes = name.toByteArray(Charsets.UTF_8)
    if (bytes.size <= PhoneName.MAX_BYTES) return name
    // Back to the start of the character the cut falls in: a continuation byte is 10xxxxxx.
    var end = PhoneName.MAX_BYTES
    while (end > 0 && (bytes[end].toInt() and UTF8_TOP_BITS) == UTF8_CONTINUATION) end--
    return String(bytes, 0, end, Charsets.UTF_8) + "…"
}

// A UTF-8 byte's top two bits, and their value in a continuation byte (10xxxxxx).
private const val UTF8_TOP_BITS = 0xC0
private const val UTF8_CONTINUATION = 0x80
