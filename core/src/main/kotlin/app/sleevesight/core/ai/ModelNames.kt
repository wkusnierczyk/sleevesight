package app.sleevesight.core.ai

/**
 * Models' names by id, for those the settings name without a listing (#444): each provider's
 * default (`Settings.DEFAULT_MODELS`), and OpenAI's text models, since OpenAI's model list gives
 * ids only (Anthropic's `display_name` and Google's `displayName` name theirs). A model not here is
 * named by its id, or by its listing where the service names it. Names as each service's models
 * page gives them, on 2026-10-25.
 */
object ModelNames {
    private val KNOWN: Map<String, String> =
        mapOf(
            // Anthropic's and Google's defaults.
            "claude-haiku-5-5" to "Claude Haiku 5.5",
            "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite",
            // OpenAI.
            "gpt-6.1-sol" to "GPT-6.1 Sol",
            "gpt-6.1" to "GPT-6.1",
            "gpt-6-astra" to "GPT-6 Astra",
            "gpt-6-luna" to "GPT-6 Luna",
            "gpt-6" to "GPT-6",
            "gpt-5.6-terra" to "GPT-5.6 Terra",
            "gpt-5.6" to "GPT-5.6",
            "gpt-5.5" to "GPT-5.5",
            "gpt-5.4-nano" to "GPT-5.4 nano",
            "gpt-5.4" to "GPT-5.4",
            "gpt-5.3-codex" to "GPT-5.3-Codex",
            "gpt-5.2" to "GPT-5.2",
            "gpt-5.1" to "GPT-5.1",
            "gpt-5-nano" to "GPT-5 nano",
            "gpt-5" to "GPT-5",
            "gpt-4.1-nano" to "GPT-4.1 nano",
            "gpt-4.1-mini" to "GPT-4.1 mini",
            "gpt-4.1" to "GPT-4.1",
            "gpt-4o-mini" to "GPT-4o mini",
            "gpt-4o" to "GPT-4o",
            "o4-mini" to "o4-mini",
            "o3" to "o3",
        )

    /** [id]'s name, if known here; null otherwise. */
    fun of(id: String): String? = KNOWN[id]
}
