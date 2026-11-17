package app.sleevesight.core.model

/** The AI services the user can choose (`design/design.md`, *Synthesis*). */
enum class ProviderId {
    ANTHROPIC,
    OPENAI,
    GOOGLE,
    OLLAMA,

    /**
     * OpenRouter (#1157): one key, pasted, that reaches other companies' models
     * (Claude, GPT, Gemini, and free ones), billed to the user's OpenRouter account.
     */
    OPENROUTER,
}
