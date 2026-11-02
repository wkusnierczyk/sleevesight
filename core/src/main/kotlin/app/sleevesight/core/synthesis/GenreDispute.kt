package app.sleevesight.core.synthesis

/**
 * Whether a reply to *Why is this …?* disputes the label it was asked to explain (#853): a sentence
 * saying the track is not of the genre ("Americana, not Folk", "isn't really folk", "not classified
 * as folk", "rather than folk", "Folk is not the right label"). The prompt forbids it
 * ([GenreReasonPrompt.LABEL_RULE]); a reply that writes one anyway is read as `NO_EVIDENCE`, so the
 * panel says the AI could not find an explanation rather than show an answer that argues against
 * the label.
 *
 * Precision first: a reply wrongly read as a dispute loses a valid explanation and may pay for a web
 * search, while the prompt's rule is what keeps most disputes from being written. So only explicit
 * forms count, and a qualification ("not purely folk", "Folk is not the whole story", "the folk
 * label does not describe every aspect") is no dispute. Two rounds of review found that open word
 * lists either let common disputes through or caught such qualifications (#864).
 *
 * Each sentence is read as its words and its commas, dashes and brackets, citations (`[W1]`) left
 * out. The genre is found as one to [MAX_GENRE_WORDS] words whose letters and digits, joined, are one
 * of its forms (its identity, `GenreLabel.identity`, or its label folded the same way: `hip-hop`,
 * `Hip Hop`), and only where its name ends there: at the sentence's end or a mark, or before any word
 * that does not make it a longer genre ([LONGER]: "folk rock", "folk revival"). It is disputed when
 *
 * - a negation ([NEGATIONS], or "rather than", "instead of") comes right before it, or with at most
 *   [MAX_BETWEEN] words between that keep the negation on it ([BETWEEN]: "not a", "isn't really",
 *   "doesn't sound like", "not classified as", "does not belong to"); or
 * - the clause after it denies it as the label in so many words ([LABEL_DENIAL]: "is not the right
 *   label", "isn't an accurate genre"; [NO_FIT]: "doesn't fit", "the folk tag does not apply here").
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
        words(
            "a an the of really truly much sound sounds like as under in into to fit fits count counts belong " +
                "belongs considered classed classified categorized categorised labelled labeled filed called",
        )

    /** The words that, after a genre's name, make it a longer genre's ("folk rock"): it does not end there. */
    val LONGER: Set<String> =
        words(
            "rock pop punk metal jazz soul blues funk country folk americana revival fusion noir rap hop core " +
                "wave gaze step house techno trance dance disco gospel reggae ska grunge emo garage baroque " +
                "opera psych",
        )

    /** The clause after the genre denying it as the label: "is not the right label", "isn't an apt genre". */
    val LABEL_DENIAL: Regex =
        Regex(
            "^(?:(?:label|tag|genre) )?(?:is|was|seems) not (?:really |quite )?(?:the |a |an )?" +
                "(?:right|correct|accurate|apt|fitting|good|proper|true) " +
                "(?:label|tag|genre|fit|description|category|term)\\b",
        )

    /** The whole clause after the genre saying it does not fit: "doesn't fit", "does not apply here". */
    val NO_FIT: Regex =
        Regex(
            "^(?:(?:label|tag|genre) )?(?:does|did) not (?:really |quite )?(?:fit|apply|suit)" +
                "(?: (?:here|it|this|her|him|them|the track|this track|the song|this song|the music))?$",
        )

    private val TOKEN = Regex("""[\p{L}\p{N}]+|[,()—–]""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val SENTENCE_END = Regex("""[.!?;:]""")
    private val CITATION = Regex("""\[[^\]\n]*]""")
    private val CONTRACTION = Regex("""\b(is|was|does|did)n t\b""")

    /** Whether [reply] says the track is not the genre whose forms are [forms] (each folded, [fold]). */
    fun disputes(
        reply: String,
        forms: Set<String>,
    ): Boolean =
        forms.isNotEmpty() &&
            reply.replace(CITATION, " ").split(SENTENCE_END).any { sentence ->
                val tokens = TOKEN.findAll(sentence.lowercase()).map { it.value }.toList()
                tokens.indices.any { start ->
                    val end = nameEnd(tokens, start, forms)
                    end != null && (negatedBefore(tokens, start) || deniedAfter(tokens, end))
                }
            }

    /** [name] folded as a genre's identity is: its letters and digits, lowercased. */
    fun fold(name: String): String = WORD.findAll(name.lowercase()).joinToString("") { it.value }

    private fun words(text: String): Set<String> = text.split(" ").toSet()

    // Where the genre's name ends, when the tokens from [start] on name it and it ends there; else null.
    private fun nameEnd(
        tokens: List<String>,
        start: Int,
        forms: Set<String>,
    ): Int? =
        (1..MAX_GENRE_WORDS)
            .map { start + it }
            .firstOrNull { end ->
                end <= tokens.size &&
                    tokens.subList(start, end).joinToString("") in forms &&
                    (end == tokens.size || tokens[end] !in LONGER)
            }

    // Whether a negation comes before [start], with only [BETWEEN] words after it.
    private fun negatedBefore(
        tokens: List<String>,
        start: Int,
    ): Boolean =
        (0..MAX_BETWEEN).any { between ->
            val at = start - between - 1
            at >= 0 &&
                tokens.subList(at + 1, start).all { it in BETWEEN } &&
                (tokens[at] in NEGATIONS || (at > 0 && (tokens[at - 1] to tokens[at]) in NEGATION_PAIRS))
        }

    // Whether the clause after the genre's name, ending at [end], denies it as the label.
    private fun deniedAfter(
        tokens: List<String>,
        end: Int,
    ): Boolean {
        val clause =
            tokens
                .drop(end)
                .takeWhile { WORD.matches(it) }
                .joinToString(" ")
                .replace(CONTRACTION, "$1 not")
        return LABEL_DENIAL.containsMatchIn(clause) || NO_FIT.matches(clause)
    }
}
