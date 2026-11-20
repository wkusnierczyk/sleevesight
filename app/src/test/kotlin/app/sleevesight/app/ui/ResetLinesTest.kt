package app.sleevesight.app.ui

import app.sleevesight.app.settings.SettingsReset
import app.sleevesight.app.ui.settings.RESET_LINES
import app.sleevesight.app.ui.settings.UNLISTED_FIELDS
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * *Reset settings?* (#960) accounts for every setting: each is kept by the reset
 * ([SettingsReset.KEPT]), listed when it changes ([RESET_LINES]), or put back unlisted
 * ([UNLISTED_FIELDS]). A setting added to [Settings] fails here until it is one of them.
 */
class ResetLinesTest {
    private val fields =
        Settings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private val listed = RESET_LINES.flatMap { it.fields }

    @Test
    fun everySettingIsKeptListedOrDeclaredUnlisted() {
        val accounted = SettingsReset.KEPT.map { it.name }.toSet() + listed + UNLISTED_FIELDS
        assertEquals("settings neither kept, listed nor unlisted", emptySet<String>(), fields - accounted)
        assertEquals("names that are no setting", emptySet<String>(), accounted - fields)
    }

    @Test
    fun eachSettingIsListedOnce() {
        assertEquals(listed.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
        assertEquals(emptySet<String>(), listed.toSet().intersect(UNLISTED_FIELDS))
    }
}
