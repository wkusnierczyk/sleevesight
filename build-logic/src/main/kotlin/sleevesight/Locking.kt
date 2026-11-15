package sleevesight

/**
 * Which classpaths ship and which get the build floors (`sleevesight.locking`, #1087). Every
 * configuration is locked (#1034); these sets say only which are held to their lockfile alone.
 */
object Locking {
    /**
     * The classpaths that ship, held to each module's `gradle.lockfile` with no build floors. An
     * Android variant whose classpaths are not here fails the build (`sleevesight.android-app`), so
     * a new one never gets the floors.
     */
    val shipping =
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
     * Whether [name] gets the build floors ([BuildFloors]): every configuration but the shipping ones
     * and the module's own code beside `main` (`jvmMain*Classpath`, `testFixtures*Classpath`), which
     * resolve as `main` does.
     */
    fun isFloored(name: String): Boolean =
        name !in shipping &&
            !(name.endsWith("Classpath") && (name.startsWith("jvmMain") || name.startsWith("testFixtures")))
}
