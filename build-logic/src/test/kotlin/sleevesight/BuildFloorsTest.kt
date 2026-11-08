package sleevesight

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildFloorsTest {
    @Test
    fun `a lower version is below its floor`() {
        assertTrue(BuildFloors.isBelow("33.4.0-jre", "33.7.2-jre"))
        assertTrue(BuildFloors.isBelow("1.80.2", "1.85"))
        assertTrue(BuildFloors.isBelow("1.3.14", "1.5.34"))
        assertTrue(BuildFloors.isBelow("4.5.6", "4.5.14"))
    }

    @Test
    fun `a shorter version is below a floor that adds a part`() {
        assertTrue(BuildFloors.isBelow("2.0.6", "2.0.6.1"))
    }

    @Test
    fun `the floor itself and higher versions are not below it`() {
        assertFalse(BuildFloors.isBelow("33.7.2-jre", "33.7.2-jre"))
        assertFalse(BuildFloors.isBelow("33.7.2-android", "33.7.2-jre"))
        assertFalse(BuildFloors.isBelow("33.8.0-jre", "33.7.2-jre"))
        assertFalse(BuildFloors.isBelow("1.86", "1.85"))
        assertFalse(BuildFloors.isBelow("1.85.2", "1.85"))
        assertFalse(BuildFloors.isBelow("2.0.6.1", "2.0.6"))
    }

    @Test
    fun `a version with no leading number is left to the resolution`() {
        assertFalse(BuildFloors.isBelow("", "1.85"))
        assertFalse(BuildFloors.isBelow("+", "1.85"))
        assertFalse(BuildFloors.isBelow("[1.0,2.0)", "1.85"))
        assertFalse(BuildFloors.isBelow("latest.release", "1.85"))
    }
}
