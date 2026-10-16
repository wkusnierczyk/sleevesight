package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckModuleGraphTest {
    @Test
    fun `a forbidden module on the compile classpath is a problem, however it got there`() {
        assertEquals(
            listOf(":app may not have :net on its compile classpath, even through another module"),
            CheckModuleGraph.compileForbidden("app", listOf("net"), listOf(":core", ":net", ":wiring")),
        )
    }

    @Test
    fun `a module with no forbidden module on its compile classpath has no problem`() {
        assertEquals(emptyList(), CheckModuleGraph.compileForbidden("app", listOf("net"), listOf(":core", ":wiring")))
        assertEquals(emptyList(), CheckModuleGraph.compileForbidden("cli", emptyList(), listOf(":net")))
    }
}
