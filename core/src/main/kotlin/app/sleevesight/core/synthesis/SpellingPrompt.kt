package app.sleevesight.core.synthesis

/**
 * The spelling check's prompt (#1293, [SearchKind.SPELLING]): when MusicBrainz finds nothing for the
 * track as the player names it, the AI service is asked, from web pages only ([WebPrompt]: *Once*'s
 * search or its own, *Up to 3 times*), how the title is spelled where the work is known.
 * The track block holds the title and artists as the player gives them and the album, nothing from
 * the sources (MusicBrainz found nothing; D12 keeps its tags out in any case). The reply names
 * another spelling of the same names, never another song or artist, with the page that spells it so;
 * `{}` when the player's spelling is the pages' own, or no page is about the track.
 */
object SpellingPrompt {
    /**
     * The version of the check's prompt, part of its answer's cache key (`CardKey.promptVersion`),
     * its own: raised when the prompt changes, so a spelling kept is asked again.
     */
    const val VERSION: Int = 1

    /** The JSON object the reply takes. */
    const val SHAPE: String = "{\"title\": \"…\", \"source\": \"https://…\"}"

    /** What is asked, last, after the data. */
    const val QUESTION: String =
        "How is this track's title spelled where the work is known?"

    /** The rules of the reply, after [WebPrompt.system]'s own. */
    val RULES: List<String> =
        listOf(
            "- title: this track's title as the pages spell it, when the title given is misspelled or carries " +
                "words that are not part of it, such as the name of the opera or the album it comes from. " +
                "Only the title, as the pages name this recording, with no quotation marks around it.",
            "- Never name another song or piece: only the same title, spelled as the pages spell it.",
            "- If the title given is spelled as the pages spell it, or no page is about this " +
                "track, reply {}.",
            "- source: the address of the page that spells the title so.",
        )

    /**
     * *Once*'s search (#1293): the title and artists as the player gives them, as a phrase, not in
     * quotes, since the title may be misspelled.
     */
    fun query(facts: SearchFacts): String {
        val by =
            facts.artists
                .takeIf { it.isNotEmpty() }
                ?.let { " by " + InsightPrompt.oneLine(it.joinToString(", ")) }
                .orEmpty()
        return InsightPrompt.oneLine(facts.title) + by
    }
}
