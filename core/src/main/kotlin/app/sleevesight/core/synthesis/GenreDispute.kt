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
 * label does not describe every aspect", "never a pop hit", "no folk purist") is no dispute. Two
 * rounds of review found that open word lists either let common disputes through or caught such
 * qualifications (#864).
 *
 * Each sentence is read as its words and its commas, dashes and brackets, citations (`[W1]`) and the
 * names passed (the track's title, release and artists: an album "No Pop", a song "Not
 * Rock") left out. The genre is found as one to [MAX_GENRE_WORDS] words whose letters and digits,
 * joined, are one of its forms (its identity, `GenreLabel.identity`, or its label folded the same way:
 * `hip-hop`, `Hip Hop`). It is disputed when
 *
 * - a negation ([NEGATIONS], or "rather than", "instead of") comes right before it, or with at most
 *   [MAX_BETWEEN] words between that keep the negation on it ([BETWEEN]: "not a", "isn't really",
 *   "doesn't sound like", "not classified as", "does not belong to"), and the genre's name ends the
 *   phrase: at the sentence's end, a mark, or a word of [ENDS_NAME] ("not folk because …", "not a folk
 *   song"), not a noun it qualifies ("no folk purist", "folk rock") or a word that hedges it ("not folk
 *   in the traditional sense", "not as folk as"); or
 * - the clause after it denies it as the label in so many words ([LABEL_DENIAL]: "is not the right
 *   label", "isn't an accurate genre", but not "… label on its own"; [NO_FIT]: "doesn't fit", "the folk
 *   tag does not apply here").
 *
 * A reply read as a dispute is kept as `NO_EVIDENCE` (`GenreReasons`), so a change here that reads
 * fewer replies as disputes needs a [GenreReasonPrompt.VERSION] bump for kept ones to be asked again.
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
            "a an the of really truly much sound sounds sounded like as under in into to fit fits count counts " +
                "belong belongs considered classed classified categorized categorised labelled labeled filed called " +
                "mention mentions mentioned describe describes described",
        )

    /** The words that may follow a negated genre's name and leave it the thing denied. */
    val ENDS_NAME: Set<String> =
        words(
            "music song songs track tracks record records album albums genre label tag at either because but and " +
                "or nor though although since",
        )

    /** The clause after the genre denying it as the label: "is not the right label", "isn't an apt genre". */
    val LABEL_DENIAL: Regex =
        Regex(
            "^(?:(?:label|tag|genre) )?(?:is|was|seems) not (?:really |quite )?(?:the |a |an )?" +
                "(?:right|correct|accurate|apt|fitting|good|proper|true) " +
                "(?:label|tag|genre|fit|description|category|term)\\b(?! on its own| alone| by itself| for all)",
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

    /**
     * Whether [reply] says the track is not the genre whose forms are [forms] (each folded, [fold]),
     * the [names] in it (the track's title, release, artists) left out.
     */
    fun disputes(
        reply: String,
        forms: Set<String>,
        names: Collection<String> = emptyList(),
    ): Boolean =
        forms.isNotEmpty() &&
            withoutNames(reply.replace(CITATION, " "), names).split(SENTENCE_END).any { sentence ->
                val tokens = TOKEN.findAll(sentence.lowercase()).map { it.value }.toList()
                tokens.indices.any { start ->
                    ends(tokens, start, forms).any { end ->
                        (endsPhrase(tokens, end) && negatedBefore(tokens, start)) || deniedAfter(tokens, end)
                    }
                }
            }

    /** [name] folded as a genre's identity is: its letters and digits, lowercased. */
    fun fold(name: String): String = WORD.findAll(name.lowercase()).joinToString("") { it.value }

    private fun words(text: String): Set<String> = text.split(" ").toSet()

    // [text] with each of [names] (not blank) replaced by a space, whatever its case, longest first.
    private fun withoutNames(
        text: String,
        names: Collection<String>,
    ): String =
        names
            .filter { it.isNotBlank() }
            .sortedByDescending { it.length }
            .fold(text) { left, name -> left.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE), " ") }

    // Where the genre's name may end, when the tokens from [start] on name it.
    private fun ends(
        tokens: List<String>,
        start: Int,
        forms: Set<String>,
    ): List<Int> =
        (1..MAX_GENRE_WORDS)
            .map { start + it }
            .filter { end -> end <= tokens.size && tokens.subList(start, end).joinToString("") in forms }

    // Whether the genre's name ending at [end] ends the phrase: the sentence's end, a mark, or [ENDS_NAME].
    private fun endsPhrase(
        tokens: List<String>,
        end: Int,
    ): Boolean = end == tokens.size || !WORD.matches(tokens[end]) || tokens[end] in ENDS_NAME

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
