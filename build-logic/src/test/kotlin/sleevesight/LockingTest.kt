package sleevesight

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockingTest {
    @Test
    fun `the locked classpaths get no floors`() {
        Locking.configurations.forEach { assertFalse(Locking.isFloored(it), it) }
    }

    @Test
    fun `the module's own code beside main gets no floors`() {
        listOf(
            "jvmMainCompileClasspath",
            "jvmMainRuntimeClasspath",
            "testFixturesCompileClasspath",
            "testFixturesRuntimeClasspath",
        ).forEach { assertFalse(Locking.isFloored(it), it) }
    }

    @Test
    fun `the tools' and the tests' classpaths get the floors`() {
        listOf(
            "androidLintTool",
            "ktlint",
            "detekt",
            "testRuntimeClasspath",
            "debugUnitTestRuntimeClasspath",
            "kotlinCompilerClasspath",
        ).forEach { assertTrue(Locking.isFloored(it), it) }
    }
}
