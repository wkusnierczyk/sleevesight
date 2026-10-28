package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the key dialog's field holds ([typed]), whether it shows it ([shown]), and whether what it
 * holds is a key read from a QR code ([scanned]), which is never shown (#702). Kept only in the
 * dialog's memory, as [KeyDialog] says.
 *
 * The field is masked at first, and again once emptied (#432). Keys are lent to a tester as a setup
 * code, and *Load keys* shows them only by their last four characters, so a key scanned into the
 * field, a setup code's or a plain key's, is never shown either: the scan sets [scanned], not a
 * comparison of the text. It holds through an edit on the phone that leaves something in the
 * field, which would still be mostly that key, and ends when the field is emptied. Text typed on a
 * computer (#394) replaces the field's whole text and is the user's own, so it ends it too.
 */
@Stable
internal class KeyField {
    /** What the field holds. */
    var typed by mutableStateOf("")
        private set

    /** Whether the field holds a key read from a QR code. */
    var scanned by mutableStateOf(false)
        private set

    // Whether the user asked to show what the field holds.
    private var asked by mutableStateOf(false)

    /** Whether the field shows what it holds: only when asked, and never a [scanned] key. */
    val shown: Boolean get() = asked && !scanned

    /** Text typed or pasted on the phone, or filled by autofill. */
    fun enter(text: String) {
        typed = text
        if (text.isEmpty()) {
            asked = false
            scanned = false
        }
    }

    /** Text typed on a computer, which replaces the field's. */
    fun typeOnComputer(text: String) {
        typed = text
        if (text.isEmpty()) asked = false
        scanned = false
    }

    /** A key read from a QR code: masked, and never shown. */
    fun scan(key: String) {
        typed = key
        asked = false
        scanned = true
    }

    /** Shows what the field holds, unless [scanned], or hides it. */
    fun show(show: Boolean) {
        asked = show && !scanned
    }
}
