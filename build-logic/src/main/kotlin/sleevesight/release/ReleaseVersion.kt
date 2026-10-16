package sleevesight.release

/**
 * The app's `versionName` and `versionCode`, both from one `<major>.<minor>.<patch>` version: the
 * release workflow passes the tag without its `v` (`design/release.md`, *Versioning*).
 *
 * The code is `major * 1_000_000 + minor * 1_000 + patch`, so a higher version always has a higher
 * code, as Google Play requires of every upload, and the code can be read back as the version.
 * Minor and patch stay below 1000, and the code at or below Play's limit of 2 100 000 000.
 */
data class ReleaseVersion(
    val name: String,
    val code: Int,
) {
    companion object {
        /** The largest `versionCode` Google Play accepts. */
        const val MAX_CODE = 2_100_000_000

        private const val PART_LIMIT = 1_000
        private val pattern = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})""")

        /** Parses [version], or fails with a message that names the expected form. */
        fun parse(version: String): ReleaseVersion {
            val match =
                requireNotNull(pattern.matchEntire(version)) {
                    "Version '$version' is not <major>.<minor>.<patch>: numbers without leading zeros, " +
                        "minor and patch below $PART_LIMIT (a release tag is v<major>.<minor>.<patch>)"
                }
            val (major, minor, patch) = match.destructured.toList().map { it.toLong() }
            val code = (major * PART_LIMIT + minor) * PART_LIMIT + patch
            require(code in 1..MAX_CODE) { "Version '$version' gives versionCode $code, outside 1..$MAX_CODE" }
            return ReleaseVersion(version, code.toInt())
        }
    }
}
