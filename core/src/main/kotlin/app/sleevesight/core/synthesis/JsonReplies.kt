package app.sleevesight.core.synthesis

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The JSON object in a model's reply (#773), as *Search with AI* and the AI checks read one
 * ([AiSearchAnswers.objectIn]): strictly parsed, and, for an answer that is one text alone, recovered
 * from a reply with a stray quote in its text ([oneTextIn], #1088).
 */
internal object JsonReplies {
    private val lenient = Json { ignoreUnknownKeys = true }
    private const val FENCE = "```"

    // The key of each answer that is one text alone, which [oneTextIn] may recover (#1088).
    private val TEXT_KEYS = mapOf(SearchKind.EXPLAIN to "explanation", SearchKind.DEFINITION to "definition")

    // JSON's structure in a string's text: a quote not escaped that closes an object (`"}`) or is
    // followed by another key (`", "sources":`), or an object opened (`{"`). Two objects one after the
    // other, or a second key, make the text JSON fragments, not one answer.
    private val STRUCTURE = Regex("""(?<!\\)"\s*}|(?<!\\)"\s*,\s*"[^"]*"\s*:|\{\s*"""")

    /**
     * The JSON object in [reply], or null when there is none: its text trimmed, a Markdown code
     * fence around it stripped (with its language, ```` ```json ````), and, with words before or
     * after it, from its first `{` to its last `}`.
     */
    fun objectIn(reply: String): JsonObject? = objectText(reply)?.let(::parsed)

    /**
     * [kind]'s answer in [reply] that [objectIn] could not read (#1088), [oneKeyIn] its key, when it
     * is one text alone: a genre's definition, *Explain the song*'s explanation; else null.
     */
    fun oneTextIn(
        reply: String,
        kind: SearchKind,
    ): JsonObject? = TEXT_KEYS[kind]?.let { oneKeyIn(reply, it) }

    /**
     * The answer at [key] in [reply] that [objectIn] could not read (#1088), or null: only an object
     * ([objectIn]'s text) that is that one key with a string value, `{"explanation": "…"}`, its text
     * from the key's opening quote to the closing `"}`, a `"` in it not escaped taken as a quote of
     * its text. A model quoting a phrase in double quotes, unescaped, costs the quote, not the answer.
     * A text that holds JSON's structure, a second key (`", "sources": `) or another object (`"}`,
     * `{"`), as two objects one after the other do, is no such object.
     */
    fun oneKeyIn(
        reply: String,
        key: String,
    ): JsonObject? {
        val pattern = Regex("""^\{\s*"${Regex.escape(key)}"\s*:\s*"(.*)"\s*}$""", RegexOption.DOT_MATCHES_ALL)
        val value =
            objectText(reply)
                ?.let { pattern.find(it) }
                ?.groupValues
                ?.get(1)
                ?.takeUnless { STRUCTURE.containsMatchIn(it) }
        return value?.let { parsed("{\"$key\": \"${quotesEscaped(it)}\"}") }
    }

    // [value], a JSON string's text, with each `"` not escaped escaped; an escape kept as it is.
    private fun quotesEscaped(value: String): String =
        buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '\\' && i + 1 < value.length -> append(c).append(value[++i])
                    c == '"' -> append("\\\"")
                    else -> append(c)
                }
                i++
            }
        }

    // The text of the JSON object in [reply] ([objectIn]), or null when it has no `{ … }`.
    private fun objectText(reply: String): String? {
        var text = reply.trim()
        if (text.startsWith(FENCE)) {
            text = text.substringAfter('\n', "").trimEnd().removeSuffix(FENCE)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start < 0 || end < start) null else text.substring(start, end + 1)
    }

    // [text] parsed as a JSON object, or null when it is none.
    private fun parsed(text: String): JsonObject? =
        try {
            lenient.parseToJsonElement(text) as? JsonObject
        } catch (
            @Suppress("SwallowedException") e: SerializationException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            null
        }
}
