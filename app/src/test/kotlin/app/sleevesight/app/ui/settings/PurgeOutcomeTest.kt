package app.sleevesight.app.ui.settings

import app.sleevesight.app.R
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretId.ANTHROPIC
import app.sleevesight.app.secrets.SecretId.EXA
import app.sleevesight.app.secrets.SecretId.LASTFM
import app.sleevesight.app.secrets.SecretState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What *Purge keys* says after a reset ([purgeFailure], #446), over every combination of the
 * storage's step, the Keystore's step, what was read after them, and the keys listed before.
 */
class PurgeOutcomeTest {
    /** One combination, and what it should say and list (null: the deletion finished). */
    private class Row(
        val removed: Boolean,
        val sealDeleted: Boolean,
        val left: Left,
        val before: Before,
        val expected: PurgeFailure?,
    )

    private enum class Left { UNREAD, EMPTY, SOME }

    private enum class Before { NONE, SOME }

    private fun read(left: Left): Map<SecretId, SecretState>? =
        when (left) {
            Left.UNREAD -> null
            Left.EMPTY -> SecretId.entries.associateWith { SecretState.ABSENT }
            // Exa absent, Last.fm still opening, Anthropic's entry that no longer opens.
            Left.SOME ->
                SecretId.entries.associateWith { SecretState.ABSENT } +
                    mapOf(ANTHROPIC to SecretState.UNREADABLE, LASTFM to SecretState.SAVED)
        }

    private fun listed(before: Before): List<SecretId> = if (before == Before.NONE) emptyList() else listOf(LASTFM, EXA)

    private fun failure(
        message: Int,
        vararg left: LeftKey,
    ) = PurgeFailure(message, left.toList())

    private val readLeft = arrayOf(LeftKey(ANTHROPIC, readable = false), LeftKey(LASTFM, readable = true))

    private val rows =
        buildList {
            // Both steps done: finished, whatever was read after.
            for (left in Left.entries) for (before in Before.entries) add(Row(true, true, left, before, null))
            // The entries removed, the Keystore key not: nothing is left; "the keys" only when some were listed.
            for (left in Left.entries) {
                add(Row(true, false, left, Before.NONE, failure(R.string.purge_keys_seal_failed)))
                add(Row(true, false, left, Before.SOME, failure(R.string.purge_keys_seal_not_deleted)))
            }
            // The Keystore key deleted, the entries not: those left can no longer be read.
            add(Row(false, true, Left.UNREAD, Before.NONE, failure(R.string.purge_keys_storage_failed)))
            add(
                Row(
                    false,
                    true,
                    Left.UNREAD,
                    Before.SOME,
                    failure(R.string.purge_keys_not_removed, LeftKey(LASTFM, false), LeftKey(EXA, false)),
                ),
            )
            for (before in Before.entries) {
                add(Row(false, true, Left.EMPTY, before, failure(R.string.purge_keys_storage_failed)))
                add(Row(false, true, Left.SOME, before, failure(R.string.purge_keys_not_removed, *readLeft)))
            }
            // Neither step done: unread, the keys listed still open.
            add(Row(false, false, Left.UNREAD, Before.NONE, failure(R.string.purge_keys_both_failed_none)))
            add(
                Row(
                    false,
                    false,
                    Left.UNREAD,
                    Before.SOME,
                    failure(R.string.purge_keys_both_failed, LeftKey(LASTFM, true), LeftKey(EXA, true)),
                ),
            )
            for (before in Before.entries) {
                add(Row(false, false, Left.EMPTY, before, failure(R.string.purge_keys_both_failed_none)))
                add(Row(false, false, Left.SOME, before, failure(R.string.purge_keys_both_failed, *readLeft)))
            }
        }

    @Test
    fun theTableHoldsEveryCombinationOnce() {
        val combinations = rows.map { listOf(it.removed, it.sealDeleted, it.left, it.before) }
        assertEquals(2 * 2 * Left.entries.size * Before.entries.size, combinations.toSet().size)
        assertEquals(combinations.size, combinations.toSet().size)
    }

    @Test
    fun eachCombinationSaysWhatItShouldAndListsTheKeysLeft() {
        val wrong =
            rows.mapNotNull { row ->
                val got = purgeFailure(ResetResult(row.removed, row.sealDeleted, read(row.left)), listed(row.before))
                val name = "removed=${row.removed} sealDeleted=${row.sealDeleted} left=${row.left} before=${row.before}"
                "$name: got $got, expected ${row.expected}".takeIf { got != row.expected }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun noMessageNamesTheKeysWhenNoneWereListedOrAreLeft() {
        // The messages that name "the keys" (or list them).
        val namingKeys =
            setOf(
                R.string.purge_keys_seal_not_deleted,
                R.string.purge_keys_not_removed,
                R.string.purge_keys_both_failed,
            )
        for (row in rows) {
            val got =
                purgeFailure(ResetResult(row.removed, row.sealDeleted, read(row.left)), listed(row.before)) ?: continue
            val noKeys = if (row.removed) row.before == Before.NONE else got.left.isEmpty()
            if (noKeys) assertEquals(row.toString(), false, got.message in namingKeys)
        }
    }
}
