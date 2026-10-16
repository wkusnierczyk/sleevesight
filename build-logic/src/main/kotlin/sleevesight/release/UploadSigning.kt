package sleevesight.release

/**
 * The upload key that signs a release build, from the environment (`design/release.md`). The
 * release workflow sets all four variables from the `release` environment's secrets.
 */
data class UploadSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    override fun toString() = "UploadSigning(storeFile=$storeFile, keyAlias=$keyAlias)"

    companion object {
        const val STORE_FILE = "SLEEVESIGHT_UPLOAD_STORE_FILE"
        const val STORE_PASSWORD = "SLEEVESIGHT_UPLOAD_STORE_PASSWORD"
        const val KEY_ALIAS = "SLEEVESIGHT_UPLOAD_KEY_ALIAS"
        const val KEY_PASSWORD = "SLEEVESIGHT_UPLOAD_KEY_PASSWORD"
        val VARIABLES = listOf(STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

        /**
         * The upload key [environment] names, or null when it names none, so a release build without
         * the key is unsigned and `./gradlew build` works anywhere. Some of the variables without the
         * others fail, rather than leave a build unsigned that was meant to be signed; an empty
         * value counts as unset, as an unset GitHub secret is passed as one.
         */
        fun from(environment: (String) -> String?): UploadSigning? {
            val values = VARIABLES.associateWith { environment(it)?.takeIf(String::isNotEmpty) }
            val missing = values.filterValues { it == null }.keys
            if (missing.size == VARIABLES.size) return null
            require(missing.isEmpty()) {
                "Release signing needs all of ${VARIABLES.joinToString()}; missing or empty: ${missing.joinToString()}"
            }
            return UploadSigning(
                storeFile = values.getValue(STORE_FILE)!!,
                storePassword = values.getValue(STORE_PASSWORD)!!,
                keyAlias = values.getValue(KEY_ALIAS)!!,
                keyPassword = values.getValue(KEY_PASSWORD)!!,
            )
        }
    }
}
