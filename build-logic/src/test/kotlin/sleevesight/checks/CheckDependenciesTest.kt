package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckDependenciesTest {
    @Test
    fun `a group entry matches every module of the group`() {
        assertTrue(CheckDependencies.moduleMatches("org.slf4j", "org.slf4j:slf4j-api:2.0.19"))
        assertTrue(CheckDependencies.moduleMatches("org.slf4j", "org.slf4j:slf4j-simple:2.0.19"))
        assertFalse(CheckDependencies.moduleMatches("org.slf4j", "org.slf4j.ext:x:1"))
    }

    @Test
    fun `a module entry matches that module only, at any version`() {
        assertTrue(CheckDependencies.moduleMatches("org.slf4j:slf4j-api", "org.slf4j:slf4j-api:2.0.19"))
        assertFalse(CheckDependencies.moduleMatches("org.slf4j:slf4j-api", "org.slf4j:slf4j-simple:2.0.19"))
        assertFalse(CheckDependencies.moduleMatches("org.slf4j:slf4j-api", "org.slf4j:slf4j-api-extra:2.0.19"))
    }

    @Test
    fun `a wildcard entry matches the group and its subgroups`() {
        assertTrue(CheckDependencies.moduleMatches("androidx.*", "androidx:core:1"))
        assertTrue(CheckDependencies.moduleMatches("androidx.*", "androidx.core:core:1"))
        assertFalse(CheckDependencies.moduleMatches("androidx.*", "androidxyz:core:1"))
    }
}
