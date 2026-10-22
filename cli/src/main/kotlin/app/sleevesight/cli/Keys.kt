package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import java.io.File

/**
 * The developer's API keys for a live run: each from its environment variable, else from the
 * keys file (`~/.config/sleevesight/keys.env`, `NAME=value` lines, `export` and quotes allowed).
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

            fun key(name: String): Secret? =
                (environment[name] ?: fromFile[name])?.trim()?.takeIf { it.isNotEmpty() }?.let(::Secret)
            return Keys(key(ANTHROPIC), key(EXA), key(LASTFM), openAi = key(OPENAI), google = key(GOOGLE))
        }

        /** The `NAME=value` lines of an env file; comments, blank lines and anything else are skipped. */
        fun parse(lines: List<String>): Map<String, String> =
            lines
                .map { it.trim().removePrefix("export ").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    val name = line.substringBefore('=').trim()
                    val value =
                        line
                            .substringAfter('=')
                            .trim()
                            .removeSurrounding("\"")
                            .removeSurrounding("'")
                    name to value
                }
    }
}
