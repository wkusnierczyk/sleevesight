package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckModuleGraphTest {
    private val modules = setOf("app", "cli", "core", "net", "wiring")

    @Test
    fun `a forbidden module on the compile classpath is a problem, however it got there`() {
        assertEquals(
            listOf(":app may not have :net on its compile classpath, even through another module"),
            CheckModuleGraph.compileForbidden("app", listOf("net"), listOf(":core", ":net", ":wiring"), modules),
        )
    }

    @Test
    fun `a module with no forbidden module on its compile classpath has no problem`() {
        assertEquals(emptyList(), CheckModuleGraph.compileForbidden("app", listOf("net"), listOf(":core", ":wiring"), modules))
        assertEquals(emptyList(), CheckModuleGraph.compileForbidden("cli", emptyList(), listOf(":net"), modules))
    }

    @Test
    fun `a forbidden name that is no module is a problem`() {
        assertEquals(
            listOf("compile-forbidden.app names an unknown module, :nett"),
            CheckModuleGraph.compileForbidden("app", listOf("nett"), listOf(":core", ":net"), modules),
        )
    }

    @Test
    fun `the modules of a rules file are its keys other than settings and compile-forbidden ones`() {
        assertEquals(
            setOf("app", "net"),
            CheckModuleGraph.modulesOf(setOf("app", "net", "compile-forbidden.app", "network-groups", "network-modules")),
        )
    }
}
