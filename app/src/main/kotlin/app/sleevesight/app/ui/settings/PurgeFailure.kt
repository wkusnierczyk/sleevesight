package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState

// What *Purge keys* says after a deletion that did not finish (#446): from the reset's result
// alone, never from the screen's state.

/** What a purge that did not finish says ([message]), and the keys it lists as [left]. */
internal data class PurgeFailure(
    @StringRes val message: Int,
    val left: List<ShownKey>,
)

/**
 * What *Purge keys* says after [result], the reset of the keys listed [before] it (in the order
 * *Secrets* shows them, each with its last characters if it could be read); null when both steps
 * succeeded. It reads nothing: a key's last characters come from [before], as a purge never
 * changes a key's value.
 *
 * - The keys left: none once the entries were removed, whatever was read after; else those read
 *   ([ResetResult.left]), each with its last characters from [before] if it still opens; and when
 *   the file could not be read, those listed [before], whose state is unknown: as listed, unless
 *   the Keystore key that sealed them was deleted, when they can no longer be read.
 * - The message names only the steps that failed, and never "the keys" when none were listed
 *   before (for the Keystore's step) or none are left (for the storage's).
 */
internal fun purgeFailure(
    result: ResetResult,
    before: List<ShownKey>,
): PurgeFailure? {
    if (result.done) return null
    val read = result.left
    val left =
        when {
            result.removed -> emptyList()
            read == null && result.sealDeleted -> before.map { ShownKey(it.id, ending = null) }
            read == null -> before
            else -> leftIn(read, before)
        }
    val keys = if (result.removed) before.isNotEmpty() else left.isNotEmpty()
    return PurgeFailure(messageOf(result, keys), left)
}

/** What a [result] that did not finish says: only the steps that failed, naming "the keys" only with [keys]. */
@StringRes
private fun messageOf(
    result: ResetResult,
    keys: Boolean,
): Int =
    when {
        result.removed && keys -> R.string.purge_keys_seal_not_deleted
        result.removed -> R.string.purge_keys_seal_failed
        !result.sealDeleted && keys -> R.string.purge_keys_both_failed
        !result.sealDeleted -> R.string.purge_keys_both_failed_none
        keys -> R.string.purge_keys_not_removed
        else -> R.string.purge_keys_storage_failed
    }

/** The keys stored in [states], each that still opens with its last characters as [before] listed them. */
private fun leftIn(
    states: Map<SecretId, SecretState>,
    before: List<ShownKey>,
): List<ShownKey> {
    val endings = before.associate { it.id to it.ending }
    return inShownOrder(states.filterValues { it != SecretState.ABSENT }.keys)
        .map { ShownKey(it, if (states[it] == SecretState.SAVED) endings[it] else null) }
}

/** [ids] in the order *Secrets* shows them, any it does not show last. */
internal fun inShownOrder(ids: Collection<SecretId>): List<SecretId> =
    SecretId.entries
        .filter { it in ids }
        .sortedBy { SHOWN_KEYS.indexOf(it).takeIf { at -> at >= 0 } ?: SHOWN_KEYS.size }
