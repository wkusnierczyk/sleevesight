package sleevesight

/**
 * Which classpaths are locked and which get the build floors (`sleevesight.locking`, #1087).
 */
object Locking {
    /**
     * The classpaths that ship, locked in each module's `gradle.lockfile`. An Android variant whose
     * classpaths are not here fails the build (`sleevesight.android-app`), so a new one is never
     * left unlocked.
     */
    val configurations =
        setOf(
            "runtimeClasspath",
            "compileClasspath",
            "releaseRuntimeClasspath",
            "releaseCompileClasspath",
            // The debug APK is the CI artifact installed on phones.
            "debugRuntimeClasspath",
            "debugCompileClasspath",
        )

    /**
     * Whether [name] gets the build floors ([BuildFloors]): every configuration but the locked ones
     * and the module's own code beside `main` (`jvmMain*Classpath`, `testFixtures*Classpath`), which
     * resolve as `main` does.
     */
    fun isFloored(name: String): Boolean =
        name !in configurations &&
            !(name.endsWith("Classpath") && (name.startsWith("jvmMain") || name.startsWith("testFixtures")))
}
