package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.keyboard.KeyboardSession
import app.sleevesight.app.keyboard.SessionLedger
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyShape

// What the key dialog makes of a QR code it scanned: the key itself (#268), or the code of a
// keyboard session (#394).

/** What a scan in the key dialog is for: the key itself, or a keyboard session's code. */
internal enum class ScanFor { KEY, KEYBOARD }

/** What a scan in the key dialog found: a key, a keyboard session, or what to say instead. */
internal class Scanned(
    val key: String? = null,
    val session: KeyboardSession? = null,
    @StringRes val failed: Int? = null,
)

/** What [scanned], a QR code's text read [for][purpose] the key [id], gives. */
internal fun scanned(
    purpose: ScanFor,
    id: SecretId,
    scanned: String,
    ledger: SessionLedger,
): Scanned =
    when (purpose) {
        ScanFor.KEY -> {
            scanFailure(id, scanned)?.let { Scanned(failed = it) } ?: Scanned(key = scanned)
        }

        ScanFor.KEYBOARD -> {
            val session = KeyboardSession.parse(scanned)
            when {
                session == null -> Scanned(failed = sessionFailure(scanned))
                !ledger.claim(session) -> Scanned(failed = R.string.keyboard_code_used)
                else -> Scanned(session = session)
            }
        }
    }

/** What the key dialog says of [scanned], a QR code's text, as the key [id]; null when it is one. */
@StringRes
private fun scanFailure(
    id: SecretId,
    scanned: String,
): Int? =
    if (KeySetup.isSetup(scanned)) {
        R.string.settings_key_scan_setup
    } else {
        when (KeyShape.check(id.keyName, scanned)) {
            null -> null
            KeyProblem.EMPTY -> R.string.settings_key_scan_empty
            KeyProblem.BAD_CHARACTERS -> R.string.settings_key_scan_characters
            KeyProblem.WRONG_SHAPE, KeyProblem.DUPLICATE -> R.string.settings_key_scan_shape
        }
    }

/** What the key dialog says of [scanned], a QR code's text that is not a keyboard session's. */
@StringRes
private fun sessionFailure(scanned: String): Int =
    if (KeyboardSession.isSessionCode(scanned)) R.string.keyboard_code_unreadable else R.string.keyboard_not_code
