package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The key dialog's field: what the text field holds ([text]), whether it shows it ([shown]), the
 * key a scan gave ([scanned]), and so what *Save* saves ([value]). Kept only in the dialog's
 * memory, as [KeyDialog] says.
 *
 * The field is masked at first, and again once emptied (#432). Keys are lent to a tester as a setup
 * code, and *Load keys* shows them only by their last four characters, so a key scanned into the
 * field, a setup code's or a plain key's, is never shown either (#702), and never enters the text
 * field: the text field holds as many [MASK] characters, drawn masked like any text, and the key is
 * kept here. So nothing that reads the text field can get the key: not the eye, which is not there
 * meanwhile, nor its undo history, a copy, the keyboard app through its input connection, an
 * autofill request or an accessibility service. Any edit of the field replaces the scanned key
 * whole: deleting empties the field, and what is typed, pasted or filled in its place is the
 * user's own, which the eye can show. Text typed on a computer (#394) replaces it too.
 */
@Stable
internal class KeyField {
    /** What the text field holds: what was typed, or, for a scanned key, [MASK] characters. */
    var text by mutableStateOf("")
        private set

    // The key a scan gave, while the field holds it; never in [text].
    private var scannedKey by mutableStateOf<String?>(null)

    // Whether the user asked to show what the field holds.
    private var asked by mutableStateOf(false)

    /** Whether the field holds a key read from a QR code. */
    val scanned: Boolean get() = scannedKey != null

    /** What *Save* saves: the scanned key, or what was typed. */
    val value: String get() = scannedKey ?: text

    /** Whether the field shows what it holds: only when asked, and never a [scanned] key. */
    val shown: Boolean get() = asked && !scanned

    /** Text typed, pasted or filled on the phone: it replaces a scanned key whole. */
    fun enter(text: String) = replace(text)

    /** Text typed on a computer, which replaces the field's, a scanned key's included. */
    fun typeOnComputer(text: String) = replace(text)

    /** A key read from a QR code: kept here, masked, and never shown. */
    fun scan(key: String) {
        scannedKey = key
        text = MASK.toString().repeat(key.length)
        asked = false
    }

    /** Shows what the field holds, unless [scanned], or hides it. */
    fun show(show: Boolean) {
        asked = show && !scanned
    }

    /**
     * Puts [text] in the field in place of what it held, without its [MASK] characters, however it
     * came: [MASK] stands for a scanned key's characters, so what an undo brings back of one is no
     * text, and no key's form holds it ([KeyShape][app.sleevesight.core.keys.KeyShape]).
     */
    private fun replace(text: String) {
        val kept = text.filterNot { it == MASK }
        scannedKey = null
        this.text = kept
        if (kept.isEmpty()) asked = false
    }

    companion object {
        /** What the text field holds for each character of a scanned key: the mask's own dot. */
        const val MASK = '•'
    }
}
