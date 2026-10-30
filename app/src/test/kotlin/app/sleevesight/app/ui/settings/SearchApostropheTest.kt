package app.sleevesight.app.ui.settings

import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** A typed `'` finds a typographic `’` (#758): "can't" finds *Can’t see your player?*. */
class SearchApostropheTest {
    private val note = IndexedRow("Can’t see your player?", listOf("Can’t see your player?"))
    private val index = listOf(IndexedSection(SettingsSection.PLAYERS, listOf("Players"), listOf(note)))

    @Test
    fun aKeyboardApostropheFindsATypographicOne() {
        assertEquals(setOf(note.title), matches(index, "can't")[SettingsSection.PLAYERS]?.rows)
    }

    @Test
    fun aTypographicApostropheStillFindsItself() {
        assertEquals(setOf(note.title), matches(index, "Can’t see")[SettingsSection.PLAYERS]?.rows)
    }

    @Test
    fun theMarkCoversTheTextAsWritten() {
        val marked = marked(note.title, "can't", SpanStyle())
        assertEquals(note.title, marked.text)
        assertEquals(listOf(0 to 5), marked.spanStyles.map { it.start to it.end })
    }
}
