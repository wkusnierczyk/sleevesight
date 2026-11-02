package app.sleevesight.core.synthesis

/**
 * Whether a reply to *Why is this …?* disputes the label it was asked to explain (#853): a sentence
 * saying the track is not of the genre ("not Folk", "isn't really folk", "neither rock nor folk",
 * "Americana rather than folk"). The prompt forbids it ([GenreReasonPrompt.LABEL_RULE]); a reply that
 * writes one anyway is read as `NO_EVIDENCE`, so the panel says the AI could not find an explanation
 * rather than show an answer that argues against the label.
 *
 * The genre is found as one to [MAX_GENRE_WORDS] words whose letters and digits, joined, are one of
 * its forms (its identity, `GenreLabel.identity`, or its label folded the same way: `hip-hop`,
 * `Hip Hop`); it is disputed when a negation ([NEGATIONS], or "rather than", "instead of") comes
 * before it in the same sentence with at most [MAX_BETWEEN] words between, each one that keeps the
 * negation on the genre ([BETWEEN]: "not a", "isn't really", "doesn't sound like"). Any other word
 * between ("not only folk", "never far from folk", "not unlike folk") is no dispute.
 */
object GenreDispute {
    /** The most words a genre's name spans ("progressive bluegrass", "rhythm and blues"). */
    const val MAX_GENRE_WORDS: Int = 4

    /** The most words between a negation and the genre it negates. */
    const val MAX_BETWEEN: Int = 3

    /** One-word negations; `t` is the end of "isn't", "doesn't", "wasn't" split at the apostrophe. */
    val NEGATIONS: Set<String> = setOf("not", "no", "never", "nor", "neither", "t")

    /** Two-word negations, as the words in order. */
    val NEGATION_PAIRS: Set<Pair<String, String>> = setOf("rather" to "than", "instead" to "of")

    /** The words that may stand between a negation and the genre while it still negates the genre. */
    val BETWEEN: Set<String> =
        (
            "a an the of really quite strictly purely simply exactly truly typically typical much " +
                "sound sounds like as under in into fit fits count counts considered classed filed called music"
        ).split(" ").toSet()

    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val SENTENCE_END = Regex("""[.!?;]""")

    /** Whether [reply] says the track is not the genre whose forms are [forms] (each folded, [fold]). */
    fun disputes(
        reply: String,
        forms: Set<String>,
    ): Boolean =
        forms.isNotEmpty() &&
            reply.split(SENTENCE_END).any { sentence ->
                val words = WORD.findAll(sentence.lowercase()).map { it.value }.toList()
                words.indices.any { start -> names(words, start, forms) && negated(words, start) }
            }

    /** [name] folded as a genre's identity is: its letters and digits, lowercased. */
    fun fold(name: String): String = WORD.findAll(name.lowercase()).joinToString("") { it.value }

    // Whether the words from [start] on name the genre.
    private fun names(
        words: List<String>,
        start: Int,
        forms: Set<String>,
    ): Boolean =
        (1..MAX_GENRE_WORDS).any { n ->
            start + n <= words.size && words.subList(start, start + n).joinToString("") in forms
        }

    // Whether a negation comes before [start], with only [BETWEEN] words after it.
    private fun negated(
        words: List<String>,
        start: Int,
    ): Boolean =
        (0..MAX_BETWEEN).any { between ->
            val at = start - between - 1
            at >= 0 &&
                words.subList(at + 1, start).all { it in BETWEEN } &&
                (words[at] in NEGATIONS || (at > 0 && (words[at - 1] to words[at]) in NEGATION_PAIRS))
        }
}
