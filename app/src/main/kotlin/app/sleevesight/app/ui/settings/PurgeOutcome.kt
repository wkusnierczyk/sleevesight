package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState

// What *Purge keys* says after a deletion that did not finish (#446): from the reset's result
// alone, never from the screen's state.

/** A key a purge left, and whether it can still be read, when the screen shows its last characters. */
internal data class LeftKey(
    val id: SecretId,
    val readable: Boolean,
)

/** What a purge that did not finish says ([message]), and the keys it lists as [left]. */
internal data class PurgeFailure(
    @StringRes val message: Int,
    val left: List<LeftKey>,
)

/**
 * What *Purge keys* says after [result], the reset of the keys listed [before] it (in the order
 * *Secrets* shows them); null when both steps succeeded.
 *
 * - The keys left: none once the entries were removed, whatever was read after; else those read
 *   ([ResetResult.left]), each readable if it still opens; and when the file could not be read,
 *   those listed [before], whose state is unknown: readable unless the Keystore key that sealed
 *   them was deleted.
 * - The message names only the steps that failed, and never "the keys" when none were listed
 *   before (for the Keystore's step) or none are left (for the storage's).
 */
internal fun purgeFailure(
    result: ResetResult,
    before: List<SecretId>,
): PurgeFailure? {
    if (result.done) return null
    val read = result.left
    val left =
        when {
            result.removed -> emptyList()
            read == null -> before.map { LeftKey(it, readable = !result.sealDeleted) }
            else -> leftIn(read)
        }
    val keys = if (result.removed) before.isNotEmpty() else left.isNotEmpty()
    val message =
        when {
            result.removed && keys -> R.string.purge_keys_seal_not_deleted
            result.removed -> R.string.purge_keys_seal_failed
            !result.sealDeleted && keys -> R.string.purge_keys_both_failed
            !result.sealDeleted -> R.string.purge_keys_both_failed_none
            keys -> R.string.purge_keys_not_removed
            else -> R.string.purge_keys_storage_failed
        }
    return PurgeFailure(message, left)
}

/** The keys stored in [states], each readable if it still opens. */
private fun leftIn(states: Map<SecretId, SecretState>): List<LeftKey> =
    inShownOrder(states.filterValues { it != SecretState.ABSENT }.keys)
        .map { LeftKey(it, readable = states[it] == SecretState.SAVED) }

/** [ids] in the order *Secrets* shows them, any it does not show last. */
internal fun inShownOrder(ids: Collection<SecretId>): List<SecretId> =
    SecretId.entries
        .filter { it in ids }
        .sortedBy { SHOWN_KEYS.indexOf(it).takeIf { at -> at >= 0 } ?: SHOWN_KEYS.size }
