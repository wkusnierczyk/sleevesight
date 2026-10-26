package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import java.io.File

/**
 * The developer's API keys for a live run: each from its environment variable, else from the
 * keys file (`~/.config/sleevesight/keys.env`, `NAME=value` lines, `export` and quotes allowed).
 * The environment is read by the `SLEEVESIGHT_` names alone, so a key set in the shell for other
 * tools (`ANTHROPIC_API_KEY`) is never spent unasked; a keys file may also use the providers' usual
 * names ([FILE_NAMES]), the `SLEEVESIGHT_` one winning.
 * A key is wrapped in a [Secret] at once and never printed: the report says only whether it is set.
 */
class Keys(
    val anthropic: Secret?,
    val exa: Secret?,
    val lastFm: Secret?,
    val openAi: Secret? = null,
    val google: Secret? = null,
) {
    /** The key of the AI service [provider], or null for one without a key here. */
    fun forProvider(provider: ProviderId): Secret? =
        when (provider) {
            ProviderId.ANTHROPIC -> anthropic
            ProviderId.OPENAI -> openAi
            ProviderId.GOOGLE -> google
            ProviderId.OLLAMA -> null
        }

    companion object {
        const val ANTHROPIC = "SLEEVESIGHT_ANTHROPIC_API_KEY"
        const val OPENAI = "SLEEVESIGHT_OPENAI_API_KEY"
        const val GOOGLE = "SLEEVESIGHT_GOOGLE_API_KEY"
        const val EXA = "SLEEVESIGHT_EXA_API_KEY"
        const val LASTFM = "SLEEVESIGHT_LASTFM_API_KEY"

        /**
         * The names a keys file may give each service's key, by the service's code in a setup code,
         * in order: the first one with a value wins, so the `SLEEVESIGHT_` name wins over the
         * provider's usual one. The API keys page (`assets/api-keys/keys.js`, `SERVICES`) reads the
         * same names, which `KeysTest` checks through the page's vectors.
         */
        val FILE_NAMES: Map<String, List<String>> =
            linkedMapOf(
                "anthropic" to listOf(ANTHROPIC, "ANTHROPIC_API_KEY"),
                "openai" to listOf(OPENAI, "OPENAI_API_KEY"),
                "google" to listOf(GOOGLE, "GEMINI_API_KEY", "GOOGLE_API_KEY"),
                "exa" to listOf(EXA, "EXA_API_KEY"),
                "lastfm" to listOf(LASTFM, "LASTFM_API_KEY"),
            )

        val DEFAULT_FILE: File = File(System.getProperty("user.home"), ".config/sleevesight/keys.env")

        /** No keys: replay needs none. */
        val NONE: Keys = Keys(null, null, null)

        /** The variable that holds [provider]'s key, or null for one without a key here. */
        fun variableOf(provider: ProviderId): String? =
            when (provider) {
                ProviderId.ANTHROPIC -> ANTHROPIC
                ProviderId.OPENAI -> OPENAI
                ProviderId.GOOGLE -> GOOGLE
                ProviderId.OLLAMA -> null
            }

        fun load(
            environment: Map<String, String> = System.getenv(),
            file: File = DEFAULT_FILE,
        ): Keys {
            val fromFile = if (file.isFile) parse(file.readLines()) else emptyMap()

            // The environment by the prefixed name only; the file by each of the service's names.
            fun key(code: String): Secret? {
                val names = FILE_NAMES.getValue(code)
                val inFile = names.firstNotNullOfOrNull { name -> fromFile[name]?.trim()?.takeIf { it.isNotEmpty() } }
                // An empty value is no key, in the environment as in the file.
                val inEnvironment = environment[names.first()]?.trim()?.takeIf { it.isNotEmpty() }
                return (inEnvironment ?: inFile)?.let(::Secret)
            }
            return Keys(key("anthropic"), key("exa"), key("lastfm"), openAi = key("openai"), google = key("google"))
        }

        /**
         * The `NAME=value` lines of an env file; comments, blank lines and anything else are skipped.
         * A name given again takes its later value, except an empty one, which is no key and so does
         * not replace a value given before it.
         */
        fun parse(lines: List<String>): Map<String, String> {
            val parsed = LinkedHashMap<String, String>()
            lines
                .map { it.trim().removePrefix("export ").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .forEach { line ->
                    val name = line.substringBefore('=').trim()
                    val value =
                        line
                            .substringAfter('=')
                            .trim()
                            .removeSurrounding("\"")
                            .removeSurrounding("'")
                    if (value.isNotBlank() || parsed[name].isNullOrBlank()) parsed[name] = value
                }
            return parsed
        }
    }
}
