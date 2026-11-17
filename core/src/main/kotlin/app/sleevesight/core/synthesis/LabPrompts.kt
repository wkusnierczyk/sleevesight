package app.sleevesight.core.synthesis

import app.sleevesight.core.ai.CompletionRequest
import kotlinx.serialization.json.JsonPrimitive

// The prompt lab (#1003), in the debug build only: prompt variants for each kind of AI text, written
// with in place of the release prompt's or compared with it from the same evidence. What the variants
// are and how one is put into a request lives here; the app holds the lab's settings, its log and the
// screens.

/**
 * A kind of AI text whose prompt the lab varies, by its [key] in the lab's settings and log: the key
 * insight, the song's, the artist's and the album's insights, the AI's genre list, a genre's reason,
 * and *Explain the song*.
 */
enum class PromptKind(
    val key: String,
) {
    KEY("key"),
    SONG("song"),
    ARTIST("artist"),
    ALBUM("album"),
    GENRE_LIST("genre_list"),
    GENRE_REASONS("genre_reasons"),
    EXPLAIN("explain"),
    ;

    companion object {
        /** The kind kept under [key], or null for none. */
        fun of(key: String): PromptKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A named variant of a kind's prompt: its [id] (`A`, `B`, …), a short [name], what it changes in a
 * few words ([description]), and its fixed [instructions], which take the place of the release
 * prompt's opening instructions (its first line, the role), [LabPrompts.ROLE] in them standing for
 * that line as the release prompt has it.
 */
data class PromptVariant(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
)

/**
 * The lab's prompt variants, and how one is put into a request. Only the fixed instructions vary: a
 * variant replaces the opening line of the prompt (of the system prompt where there is one), the
 * role, and the app still adds the data guard, the evidence, the rules and the reply's format, so the
 * citations and the grounding check (`GroundingCheck`) hold for every variant. The kind of a request
 * is told by that opening line ([kindOf]), so the lab needs nothing from the pipeline that builds it.
 *
 * The variants are drafts from the open prompt questions: the song whose own lines only list it
 * (#958: the artist's sound, the plain gap sentence), genres the sources name in their text (#1000)
 * and Instrumental only where a source says it of this track (#998).
 */
@Suppress("TooManyFunctions") // The variants, how a request's kind is told, and how one is put in.
object LabPrompts {
    /** The release prompt, as in the Play build: no variant. */
    const val RELEASE: String = "release"

    /** The tester's own instructions, edited in the lab. */
    const val CUSTOM: String = "custom"

    /** In a variant's instructions, the release prompt's opening line as built for this request. */
    const val ROLE: String = "{role}"

    /** The most variants a comparison writes, and the fewest. */
    const val MOST_COMPARED: Int = 4
    const val FEWEST_COMPARED: Int = 2

    /** [kind]'s variants, the release prompt and *Custom* apart. */
    fun variants(kind: PromptKind): List<PromptVariant> = VARIANTS.getValue(kind)

    /** [kind]'s variant [id], or null for the release prompt, *Custom* or an id it does not have. */
    fun variant(
        kind: PromptKind,
        id: String,
    ): PromptVariant? = variants(kind).firstOrNull { it.id == id }

    /**
     * The release prompt's opening line for [kind], as it is in its most common form (the genre list
     * from the model's own knowledge, a genre's reason without it): what *Custom* starts from, and
     * what [ROLE] reads as where the lab shows a variant's instructions.
     */
    fun release(kind: PromptKind): String =
        when (kind) {
            PromptKind.KEY -> InsightPrompt.ROLE
            PromptKind.SONG -> ItemPrompt.task(CardKind.SONG)
            PromptKind.ARTIST -> ItemPrompt.task(CardKind.ARTIST)
            PromptKind.ALBUM -> ItemPrompt.task(CardKind.ALBUM)
            PromptKind.GENRE_LIST -> firstLine(GenreListPrompt.system(GenreListMode.KNOWLEDGE))
            PromptKind.GENRE_REASONS -> firstLine(GenreReasonPrompt.system(allowModel = false))
            PromptKind.EXPLAIN -> firstLine(ExplainPrompt.system(limit = null))
        }

    /** [instructions] as the lab shows them: [ROLE] read as [kind]'s release line ([release]). */
    fun shown(
        kind: PromptKind,
        instructions: String,
    ): String = instructions.replace(ROLE, release(kind))

    /** The kind of AI text [request] asks for, told by its opening line; null for any the lab does not vary. */
    fun kindOf(request: CompletionRequest): PromptKind? = kindOfLine(firstLine(request.system ?: request.prompt))

    /** The kind whose prompt opens with [line], or null. */
    fun kindOfLine(line: String): PromptKind? =
        when {
            line == InsightPrompt.ROLE -> PromptKind.KEY
            line == ItemPrompt.task(CardKind.SONG) || line == ItemPrompt.PARTS_TASK -> PromptKind.SONG
            line == ItemPrompt.task(CardKind.ARTIST) -> PromptKind.ARTIST
            line == ItemPrompt.task(CardKind.ALBUM) -> PromptKind.ALBUM
            line.startsWith(GENRE_LIST_OPENING) -> PromptKind.GENRE_LIST
            line.startsWith(GENRE_REASON_OPENING) -> PromptKind.GENRE_REASONS
            line.startsWith(EXPLAIN_OPENING) -> PromptKind.EXPLAIN
            else -> null
        }

    /**
     * [request] with its opening line (the system prompt's, or the prompt's where it has none) in
     * place of [instructions], [ROLE] in them read as that line; [request] itself for blank ones.
     */
    fun withInstructions(
        request: CompletionRequest,
        instructions: String,
    ): CompletionRequest {
        if (instructions.isBlank()) return request
        val system = request.system
        return if (system != null) {
            request.copy(system = replaceFirstLine(system, instructions))
        } else {
            request.copy(prompt = replaceFirstLine(request.prompt, instructions))
        }
    }

    /** The opening line [request] is written with: its system prompt's, or its prompt's. */
    fun opening(request: CompletionRequest): String = firstLine(request.system ?: request.prompt)

    /**
     * The text a reader is shown of [reply], [kind]'s: *Explain the song*'s explanation out of its
     * JSON object, where it has one; any other reply as it came.
     */
    fun shownReply(
        kind: PromptKind,
        reply: String,
    ): String {
        if (kind != PromptKind.EXPLAIN) return reply.trim()
        val explanation = JsonReplies.objectIn(reply)?.get("explanation") as? JsonPrimitive
        return explanation?.takeIf { it.isString }?.content?.trim() ?: reply.trim()
    }

    /**
     * The lab's kind of a card kept as [kind] about [subject] (`CardKey`), whose key a variant in use
     * sets apart; null for a card the lab does not vary.
     */
    fun kindOfCard(
        kind: CardKind,
        subject: String?,
    ): PromptKind? =
        when (kind) {
            CardKind.INSIGHT -> PromptKind.KEY
            CardKind.SONG -> PromptKind.SONG
            CardKind.ARTIST -> PromptKind.ARTIST
            CardKind.ALBUM -> PromptKind.ALBUM
            CardKind.GENRE -> PromptKind.GENRE_REASONS
            CardKind.GENRE_LIST -> PromptKind.GENRE_LIST
            CardKind.SEARCH -> PromptKind.EXPLAIN.takeIf { subject == SearchKind.EXPLAIN.key }
            else -> null
        }

    private fun firstLine(text: String): String = text.substringBefore('\n')

    private fun replaceFirstLine(
        text: String,
        instructions: String,
    ): String {
        val first = firstLine(text)
        // One line, as the release's: the prompt's other lines keep their places.
        val line =
            instructions
                .replace(ROLE, first)
                .lines()
                .joinToString(" ") { it.trim() }
                .trim()
        return line + text.substring(first.length)
    }

    private const val GENRE_LIST_OPENING = "You name the genres of one recorded track for a music app"
    private const val GENRE_REASON_OPENING = "You explain a genre label on a music app's now-playing screen."
    private const val EXPLAIN_OPENING = "You explain what one song is about for a music app"

    private val VARIANTS: Map<PromptKind, List<PromptVariant>> =
        mapOf(
            PromptKind.KEY to
                listOf(
                    PromptVariant(
                        "A",
                        "Music first",
                        "What the track sounds like before release facts.",
                        "$ROLE When the evidence says what the track sounds like, open with that, before any " +
                            "release fact.",
                    ),
                    PromptVariant(
                        "B",
                        "One fact",
                        "The single most surprising fact, in two sentences.",
                        "$ROLE Tell only the single most surprising fact the evidence holds, in at most two " +
                            "sentences.",
                    ),
                ),
            PromptKind.SONG to
                listOf(
                    PromptVariant(
                        "A",
                        "The artist's sound",
                        "When the song's own lines only list it: one sentence on the artist's sound, named as " +
                            "theirs, cited to the artist's line.",
                        "$ROLE When no line describes the song itself, add one sentence on the artist's sound " +
                            "from the artist's lines, saying it is the artist's, cited to those lines.",
                    ),
                    PromptVariant(
                        "B",
                        "Gap first",
                        "“The sources give only release details for this song.” before the details, when no " +
                            "line describes the song.",
                        "$ROLE When no line describes the song itself, begin with exactly: \"The sources give " +
                            "only release details for this song.\", then the release details.",
                    ),
                    PromptVariant(
                        "C",
                        "Sound before dates",
                        "What the music is like first, release data last.",
                        "$ROLE Say first what the music is like, from the lines that describe it; release dates " +
                            "and labels last.",
                    ),
                ),
            PromptKind.ARTIST to
                listOf(
                    PromptVariant(
                        "A",
                        "Sound first",
                        "The music before the biography.",
                        "$ROLE Say what the artist's music sounds like first, then the biography.",
                    ),
                ),
            PromptKind.ALBUM to
                listOf(
                    PromptVariant(
                        "A",
                        "Place in the career",
                        "Where the album stands among the artist's records, first.",
                        "$ROLE Open with where the album stands among the artist's records, when the evidence " +
                            "says so.",
                    ),
                ),
            PromptKind.GENRE_LIST to
                listOf(
                    PromptVariant(
                        "A",
                        "Named genres",
                        "Genres the artist's own lines name count as the artist's.",
                        "$ROLE Genres that descriptions of the artist name count as the artist's sound, not as " +
                            "this track's, unless this track is said to have them.",
                    ),
                    PromptVariant(
                        "B",
                        "Instrumental only for the track",
                        "Instrumental only when a source says it of this track.",
                        "$ROLE Name Instrumental only when you know this recording has no vocals, not because " +
                            "the artist often plays instrumentals.",
                    ),
                ),
            PromptKind.GENRE_REASONS to
                listOf(
                    PromptVariant(
                        "A",
                        "Quote first",
                        "Open with the line that names the genre.",
                        "$ROLE Open with what the line that names the genre says, then why it fits.",
                    ),
                ),
            PromptKind.EXPLAIN to
                listOf(
                    PromptVariant(
                        "A",
                        "Lines first",
                        "Quote the lines before explaining them.",
                        "$ROLE Name what the pages say the lines are about before explaining them.",
                    ),
                ),
        )
}
