package io.github.wkusnierczyk.sleevesight.core.pipeline

import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.core.settings.WriteAutomatically
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoWriteTest {
    @Test
    fun `each mode writes the changes D5 says, and only after the user's first action`() {
        val expected =
            mapOf(
                WriteAutomatically.ON_EVERY_TRACK_CHANGE to ChangeCause.entries.toSet(),
                WriteAutomatically.WHEN_I_SKIP to setOf(ChangeCause.USER_ACTION, ChangeCause.SKIP_IN_PLAYER),
                WriteAutomatically.NEVER to emptySet(),
            )
        for ((mode, writes) in expected) {
            val settings = Settings(writeAutomatically = mode)
            for (cause in ChangeCause.entries) {
                assertEquals(cause in writes, AutoWrite.writes(settings, cause, userHasActed = true), "$mode $cause")
                assertEquals(false, AutoWrite.writes(settings, cause, userHasActed = false), "$mode $cause")
                assertEquals(
                    false,
                    AutoWrite.writes(settings.copy(writeInsights = false), cause, userHasActed = true),
                    "$mode $cause, insights off",
                )
            }
        }
    }

    @Test
    fun `the default writes on every track change`() {
        assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, Settings().writeAutomatically)
    }
}
