package app.sleevesight.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key dialog's field (#702): a scanned key is kept apart from the text field, which holds only
 * mask characters for it, so neither an undo nor a redo, which bring back what the text field held,
 * can bring the key back; any edit replaces it whole.
 */
class KeyFieldTest {
    private val key = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)

    /** What the text field held while the field held [key]: what an undo or a redo brings back. */
    private val masks = KeyField.MASK.toString().repeat(key.length)

    @Test
    fun aScannedKeyIsSavedButIsNotInTheTextFieldNorShown() {
        val field = KeyField()
        field.scan(key)
        assertEquals(key, field.value)
        assertEquals(masks, field.text)
        assertTrue(field.scanned)
        field.show(true)
        assertFalse(field.shown)
    }

    @Test
    fun anUndoOrRedoAfterAScannedKeyIsDeletedBringsBackNoKey() {
        val field = KeyField()
        field.scan(key)
        field.enter("")
        // The undo brings back what the text field held; the redo empties it again.
        field.enter(masks)
        assertEquals("", field.text)
        assertEquals("", field.value)
        assertFalse(field.scanned)
        field.enter("")
        assertEquals("", field.value)
    }

    @Test
    fun anUndoAfterTextTypedOnAComputerReplacedAScannedKeyBringsBackNoKey() {
        val field = KeyField()
        field.scan(key)
        field.typeOnComputer("sk-ant-from-the-computer")
        assertFalse(field.scanned)
        field.enter(masks)
        assertEquals("", field.value)
    }

    @Test
    fun anUndoAfterTypingOverAScannedKeyBringsBackNoKey() {
        val field = KeyField()
        field.scan(key)
        field.enter(masks + "x")
        assertEquals("x", field.value)
        field.show(true)
        assertTrue(field.shown)
        field.enter(masks)
        assertEquals("", field.value)
        assertFalse("emptied, masked again", field.shown)
    }

    @Test
    fun aKeyTypedOnAComputerOrByHandIsTheUsersOwnAndCanBeShown() {
        val field = KeyField()
        field.typeOnComputer("sk-ant-typed")
        field.show(true)
        assertTrue(field.shown)
        field.enter("sk-ant-other")
        assertTrue(field.shown)
        assertEquals("sk-ant-other", field.value)
    }
}
