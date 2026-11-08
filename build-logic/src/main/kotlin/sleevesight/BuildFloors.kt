package sleevesight

import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.VersionCatalog

/**
 * Floors for the libraries the build's own tools bring in (#1087): the version catalog's
 * `build-floor-*` entries, each the lowest version of a library with no known vulnerability.
 * [raise] lifts a requested version below its floor to the floor, and leaves every other request,
 * and every other library, as it is.
 */
object BuildFloors {
    /** The catalog aliases of the floors, as Gradle normalises `build-floor-*`. */
    private const val ALIAS_PREFIX = "build.floor."

    /** `group:name` to the floor's version, for every `build-floor-*` entry of [catalog]. */
    fun from(catalog: VersionCatalog): Map<String, String> =
        catalog.libraryAliases
            .filter { it.startsWith(ALIAS_PREFIX) }
            .associate { alias ->
                val library = catalog.findLibrary(alias).get().get()
                library.module.toString() to library.versionConstraint.requiredVersion
            }

    /** Raises [details]'s requested version to its library's floor in [floors], when it is below. */
    fun raise(
        details: DependencyResolveDetails,
        floors: Map<String, String>,
    ) {
        val requested = details.requested
        val floor = floors["${requested.group}:${requested.name}"] ?: return
        if (isBelow(requested.version.orEmpty(), floor)) {
            details.useVersion(floor)
            details.because("The lowest version with no known vulnerability (#1087)")
        }
    }

    /**
     * Whether [version] is below [floor], by their leading numeric parts (`33.4.0-jre` is 33, 4, 0;
     * a missing part counts as 0). A version with no leading number (a range, `+`, `latest.release`,
     * none at all) is never below: it is left to the resolution.
     */
    fun isBelow(
        version: String,
        floor: String,
    ): Boolean {
        val parts = numericParts(version)
        if (parts.isEmpty()) return false
        val floorParts = numericParts(floor)
        for (i in 0 until maxOf(parts.size, floorParts.size)) {
            val a = parts.getOrElse(i) { 0 }
            val b = floorParts.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        Regex("""^\d+(\.\d+)*""")
            .find(version)
            ?.value
            ?.split('.')
            ?.map { it.toInt() }
            .orEmpty()
}
