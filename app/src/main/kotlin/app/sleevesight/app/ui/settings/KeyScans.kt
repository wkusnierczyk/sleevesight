package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.keyboard.KeyboardSession
import app.sleevesight.app.keyboard.SessionLedger
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyShape
import app.sleevesight.core.keys.SetupRead

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
            // A keyboard session's code is never a key: it starts that session (#432).
            when {
                KeyboardSession.isSessionCode(scanned) -> scanned(ScanFor.KEYBOARD, id, scanned, ledger)
                KeySetup.isSetup(scanned) -> setupKey(id, KeySetup.read(scanned))
                else -> scanFailure(id, scanned)?.let { Scanned(failed = it) } ?: Scanned(key = scanned)
            }
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

/**
 * What a setup code [read] gives the key [id]'s field (#541): its key, when the code holds that key
 * alone, as the API keys page makes it from one key; and otherwise what to say. That key alone
 * refused says why, as a bare key would; a code that names another service points to *Load keys*,
 * which shows what it holds, as does one with lines this version cannot read; a code of a later
 * version asks for an update, and an empty one says so.
 */
private fun setupKey(
    id: SecretId,
    read: SetupRead,
): Scanned {
    val failure = setupFailure(id, read)
    // No failure: the code holds this key alone, and it was not refused.
    val key = (read as? SetupRead.Setup)?.keys?.get(id.keyName)
    return when {
        failure != null -> Scanned(failed = failure)
        key != null -> Scanned(key = key.reveal())
        else -> Scanned(failed = R.string.settings_key_scan_setup)
    }
}

/** What the key [id]'s field says of the setup code [read] ([setupKey]); null when it fills the field. */
@StringRes
private fun setupFailure(
    id: SecretId,
    read: SetupRead,
): Int? {
    val setup = read as? SetupRead.Setup
    val named = setup?.let { it.keys.keys + it.refused.keys }.orEmpty()
    val unread = setup != null && (setup.unknown.isNotEmpty() || setup.unreadable > 0)
    return when {
        read is SetupRead.Unsupported -> R.string.settings_key_scan_setup_newer
        setup == null -> R.string.settings_key_scan_setup
        named.any { it != id.keyName } -> R.string.settings_key_scan_setup
        unread && id.keyName in named -> R.string.settings_key_scan_setup_more
        unread -> R.string.settings_key_scan_setup_none
        named.isEmpty() -> R.string.settings_key_scan_empty
        else -> setup.refused[id.keyName]?.let(::problemOf)
    }
}

/** What the key dialog says of [scanned], a QR code's text, as the key [id]; null when it is one. */
@StringRes
private fun scanFailure(
    id: SecretId,
    scanned: String,
): Int? = KeyShape.check(id.keyName, scanned)?.let(::problemOf)

/** What the key dialog says of a scanned key with [problem]. */
@StringRes
private fun problemOf(problem: KeyProblem): Int =
    when (problem) {
        KeyProblem.EMPTY -> R.string.settings_key_scan_empty
        KeyProblem.BAD_CHARACTERS -> R.string.settings_key_scan_characters
        KeyProblem.WRONG_SHAPE -> R.string.settings_key_scan_shape
        KeyProblem.DUPLICATE -> R.string.settings_key_scan_duplicate
    }

/** What the key dialog says of [scanned], a QR code's text that is not a keyboard session's. */
@StringRes
private fun sessionFailure(scanned: String): Int =
    if (KeyboardSession.isSessionCode(scanned)) R.string.keyboard_code_unreadable else R.string.keyboard_not_code
