package io.github.wkusnierczyk.sleevesight.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SleevesightTest {
    @Test
    fun `the module builds and its tests run`() {
        assertEquals("Sleevesight", Sleevesight.NAME)
    }
}
