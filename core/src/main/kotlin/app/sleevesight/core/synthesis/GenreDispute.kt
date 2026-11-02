package app.sleevesight.core.synthesis

/**
 * Whether a reply to *Why is this …?* disputes the label it was asked to explain (#853): a sentence
 * saying the track is not of the genre ("not Folk", "isn't really folk", "not classified as folk",
 * "neither rock nor folk", "Americana rather than folk", "Folk is not the right label"). The prompt
 * forbids it ([GenreReasonPrompt.LABEL_RULE]); a reply that writes one anyway is read as
 * `NO_EVIDENCE`, so the panel says the AI could not find an explanation rather than show an answer
 * that argues against the label.
 *
 * Each sentence is read as its words, citations (`[W1]`) left out. The genre is found as one to
 * [MAX_GENRE_WORDS] words whose letters and digits, joined, are one of its forms (its identity,
 * `GenreLabel.identity`, or its label folded the same way: `hip-hop`, `Hip Hop`), and only where its
 * name ends there: the word after it, if any, is one of [AFTER_NAME] ("not folk music", "not folk but
 * Americana", "Folk is"), so in "folk, not folk rock" the negation is on another genre. It is
 * disputed in two ways:
 *
 * - a negation ([NEGATIONS], or "rather than", "instead of") before it, with at most [MAX_BETWEEN]
 *   words between, each one that keeps the negation on the genre ([BETWEEN]: "not a", "isn't
 *   really", "doesn't sound like", "not classified as"); any other word between ("not only folk",
 *   "never far from folk", "not unlike folk", "not purely folk") is no dispute;
 * - the genre first, then at most [MAX_BETWEEN] words of [SUBJECT], a verb ([VERBS]: "is", "isn't",
 *   "does") among them, a negation, and a word that turns it on the label ([LABEL_TURN]: "Folk is
 *   not the right label", "the folk tag doesn't fit"); "Folk is not loud", or "Folk is not the only
 *   thread" ([NOT_DISPUTING] in the [NOT_DISPUTING_REACH] words after the negation), is no dispute.
 */
object GenreDispute {
    /** The most words a genre's name spans ("progressive bluegrass", "rhythm and blues"). */
    const val MAX_GENRE_WORDS: Int = 4

    /** The most words between a negation and the genre, or between the genre and its negation. */
    const val MAX_BETWEEN: Int = 3

    /** One-word negations; `t` is the end of "isn't", "doesn't", "wasn't" split at the apostrophe. */
    val NEGATIONS: Set<String> = setOf("not", "no", "never", "nor", "neither", "t")

    /** Two-word negations, as the words in order. */
    val NEGATION_PAIRS: Set<Pair<String, String>> = setOf("rather" to "than", "instead" to "of")

    /** The words that may stand between a negation and the genre while it still negates the genre. */
    val BETWEEN: Set<String> =
        words(
            "a an the of really truly much sound sounds like as under in into to fit fits count counts belong " +
                "belongs considered classed classified categorized categorised labelled labeled described filed " +
                "called music",
        )

    /** The verbs that may follow the genre in a denial of it. */
    val VERBS: Set<String> = words("is was does did isn wasn doesn didn seems")

    /** The words that may stand between the genre and the negation after it, a verb among them. */
    val SUBJECT: Set<String> = words("label tag genre here really s") + VERBS

    /** The words that may follow a genre's name where the name ends. */
    val AFTER_NAME: Set<String> =
        words(
            "music song songs record records track tracks album albums artist artists singer band at all in " +
                "either but and or nor as itself though per",
        ) + SUBJECT

    /** The words after a genre-first negation that turn it on the label. */
    val LABEL_TURN: Set<String> =
        words(
            "the a an right correct accurate apt fitting fit fits describe describes suit suits really quite " +
                "what how it its this her his their",
        )

    /** How many words after a genre-first negation [NOT_DISPUTING] is looked for in. */
    const val NOT_DISPUTING_REACH: Int = 2

    /** The words that, soon after a genre-first negation, show it is no denial ("not the only"). */
    val NOT_DISPUTING: Set<String> = words("only just merely simply all")

    private val WORD = Regex("""[\p{L}\p{N}]+""")
    private val SENTENCE_END = Regex("""[.!?;:]""")
    private val CITATION = Regex("""\[[^\]\n]*]""")

    /** Whether [reply] says the track is not the genre whose forms are [forms] (each folded, [fold]). */
    fun disputes(
        reply: String,
        forms: Set<String>,
    ): Boolean =
        forms.isNotEmpty() &&
            reply.replace(CITATION, " ").split(SENTENCE_END).any { sentence ->
                val words = WORD.findAll(sentence.lowercase()).map { it.value }.toList()
                words.indices.any { start ->
                    val end = nameEnd(words, start, forms)
                    end != null && (negatedBefore(words, start) || deniedAfter(words, end))
                }
            }

    /** [name] folded as a genre's identity is: its letters and digits, lowercased. */
    fun fold(name: String): String = WORD.findAll(name.lowercase()).joinToString("") { it.value }

    private fun words(text: String): Set<String> = text.split(" ").toSet()

    // Where the genre's name ends, when the words from [start] on name it and it ends there; else null.
    private fun nameEnd(
        words: List<String>,
        start: Int,
        forms: Set<String>,
    ): Int? =
        (1..MAX_GENRE_WORDS)
            .map { start + it }
            .firstOrNull { end ->
                end <= words.size &&
                    words.subList(start, end).joinToString("") in forms &&
                    (end == words.size || words[end] in AFTER_NAME)
            }

    // Whether a negation comes before [start], with only [BETWEEN] words after it.
    private fun negatedBefore(
        words: List<String>,
        start: Int,
    ): Boolean =
        (0..MAX_BETWEEN).any { between ->
            val at = start - between - 1
            at >= 0 &&
                words.subList(at + 1, start).all { it in BETWEEN } &&
                (words[at] in NEGATIONS || (at > 0 && (words[at - 1] to words[at]) in NEGATION_PAIRS))
        }

    // Whether, after the genre's name ending at [end], a verb and a negation turn on the label.
    private fun deniedAfter(
        words: List<String>,
        end: Int,
    ): Boolean =
        (1..MAX_BETWEEN).any { between ->
            val at = end + between
            val subject = words.subList(end, minOf(at, words.size))
            at < words.size - 1 &&
                subject.all { it in SUBJECT } &&
                subject.any { it in VERBS } &&
                words[at] in NEGATIONS &&
                words[at + 1] in LABEL_TURN &&
                words.subList(at + 1, minOf(at + 1 + NOT_DISPUTING_REACH, words.size)).none { it in NOT_DISPUTING }
        }
}
