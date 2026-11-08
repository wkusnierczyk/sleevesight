package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a regex in a module's Kotlin sources has a `{` or `}` that Android cannot compile
 * (#1113): one not escaped, outside a character class, and not a quantifier (`{2}`, `{2,}`,
 * `{2,3}`) or part of an escape (`\p{L}`, `\P{..}`, `\x{..}`, `\N{..}`) or a `\Q…\E` quote. The
 * JVM takes such a brace as a literal; Android's ICU regex engine rejects it (`U_REGEX_RULE_SYNTAX`),
 * so the regex throws there only, often in an object's initializer.
 *
 * It reads the source, not the compiled classes, and finds a regex where it is written:
 * - the first argument of `Regex(…)` or `Pattern.compile(…)`: its string literals and `const val`s,
 *   in order; a call, a lambda or a variable in it adds nothing (nested brackets are skipped), and a
 *   call with `RegexOption.LITERAL` is not read;
 * - a string literal or a `const val` straight before `.toRegex(`, unless its options hold
 *   `RegexOption.LITERAL`;
 * - each element (literals and constants joined by `+`; not a map's key, before `to`) of a `listOf`,
 *   `setOf`, `arrayOf`, `sequenceOf` or `mapOf` call that is mapped with a lambda calling `Regex(`, as in
 *   `listOf("a", "b").map { Regex(it) }` (not when that call's options hold `RegexOption.LITERAL`).
 *
 * A template (`$NAME`, `${NAME}`) and a name in the expression are replaced by the `const val` of
 * that name in the same file, or else in another file the check reads; any other template or name
 * becomes nothing. A regex built elsewhere (a plain `val`, a function's parameter, another module's
 * constant) is not read: the check is a guard for the common forms, not a proof.
 */
abstract class CheckRegexBraces : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val files =
            sources.asFileTree.files
                .filter { it.extension == "kt" }
                .sorted()
                .map { it.path to it.readText() }
        val problems = problemsIn(files)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Regex braces Android's ICU engine rejects (escape them, `\\{` and `\\}`):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** Each regex in [files] (path to text) with a brace [bareBraces] finds, as `path:line: …`. */
        fun problemsIn(files: List<Pair<String, String>>): List<String> {
            val lexed = files.map { (path, text) -> path to Lexer(text).tokens() }
            val shared = lexed.flatMap { (_, tokens) -> constantsIn(tokens).entries }.associate { it.key to it.value }
            return lexed.flatMap { (path, tokens) ->
                val consts = Constants(constantsIn(tokens), shared)
                regexesIn(tokens)
                    .map { (line, expression) -> line to consts.value(expression) }
                    .filter { (_, pattern) -> bareBraces(pattern).isNotEmpty() }
                    .distinct()
                    .map { (line, pattern) -> "$path:$line: $pattern" }
            }
        }

        /** The regexes [text] (one Kotlin file) writes, each as its line and its text. */
        fun regexesInFile(text: String): List<Pair<Int, String>> {
            val tokens = Lexer(text).tokens()
            val consts = Constants(constantsIn(tokens), emptyMap())
            return regexesIn(tokens)
                .map { (line, expression) -> line to consts.value(expression) }
                .filter { (_, pattern) -> pattern.isNotEmpty() }
        }

        /**
         * The offsets of the braces in [pattern] that are not escaped, not in a character class,
         * and not a quantifier or part of an escape that takes braces; empty when there are none.
         */
        fun bareBraces(pattern: String): List<Int> {
            val found = mutableListOf<Int>()
            var classDepth = 0
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' && i + 1 < pattern.length -> i = afterEscape(pattern, i)
                    c == '[' -> {
                        classDepth++
                        i++
                    }
                    c == ']' && classDepth > 0 -> {
                        classDepth--
                        i++
                    }
                    classDepth > 0 -> i++
                    c == '{' -> {
                        val quantifier = QUANTIFIER.matchAt(pattern, i)
                        if (quantifier == null) {
                            found += i
                            i++
                        } else {
                            i = quantifier.range.last + 1
                        }
                    }
                    c == '}' -> {
                        found += i
                        i++
                    }
                    else -> i++
                }
            }
            return found
        }

        // Where the escape at [start] (a backslash) ends: after `\Q…\E`, after `\p{…}` and the other
        // escapes with braces, else after the escaped character.
        private fun afterEscape(
            pattern: String,
            start: Int,
        ): Int {
            val next = pattern[start + 1]
            return when {
                next == 'Q' -> pattern.indexOf("\\E", start + 2).let { if (it < 0) pattern.length else it + 2 }
                next in BRACED_ESCAPES && pattern.getOrNull(start + 2) == '{' ->
                    pattern.indexOf('}', start + 3).let { if (it < 0) pattern.length else it + 1 }
                else -> start + 2
            }
        }
    }
}

// The constants a file sees: its own first, then the other files'.
private class Constants(
    private val own: Map<String, List<Token>>,
    private val shared: Map<String, List<Token>>,
) {
    private val resolving = mutableSetOf<String>()

    fun value(expression: List<Token>): String =
        expression.joinToString("") { token ->
            when (token) {
                is Token.Str ->
                    token.parts.joinToString("") { part ->
                        when (part) {
                            is Part.Text -> part.text
                            is Part.Ref -> template(part)
                        }
                    }
                is Token.Name -> named(token.text)
                else -> ""
            }
        }

    private fun template(ref: Part.Ref): String {
        val source = ref.source.trim()
        return when {
            source == "'$'" -> "$"
            source.matches(QUALIFIED_NAME) -> named(source.substringAfterLast('.'))
            else -> ""
        }
    }

    private fun named(name: String): String {
        val tokens = own[name] ?: shared[name] ?: return ""
        if (!resolving.add(name)) return ""
        return try {
            value(tokens)
        } finally {
            resolving.remove(name)
        }
    }

    companion object {
        private val QUALIFIED_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
    }
}

private sealed class Part {
    data class Text(
        val text: String,
    ) : Part()

    data class Ref(
        val source: String,
    ) : Part()
}

private sealed class Token {
    abstract val line: Int

    data class Str(
        val parts: List<Part>,
        override val line: Int,
    ) : Token()

    data class Name(
        val text: String,
        override val line: Int,
    ) : Token()

    data class Punct(
        val char: Char,
        override val line: Int,
    ) : Token()
}

// Kotlin's tokens, as far as the check needs them: strings (with their templates), names and
// single punctuation characters; comments, whitespace and character literals are dropped.
private class Lexer(
    private val text: String,
) {
    private var i = 0
    private var line = 1

    fun tokens(): List<Token> {
        val found = mutableListOf<Token>()
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> skipTo("\n")
                text.startsWith("/*", i) -> skipBlockComment()
                text.startsWith("\"\"\"", i) -> found += rawString()
                c == '"' -> found += plainString()
                c == '\'' -> skipCharLiteral()
                c.isLetter() || c == '_' -> found += name()
                c == '`' -> found += quotedName()
                c.isWhitespace() -> advance()
                else -> {
                    found += Token.Punct(c, line)
                    advance()
                }
            }
        }
        return found
    }

    private fun advance(count: Int = 1) {
        repeat(count) {
            if (i < text.length && text[i] == '\n') line++
            i++
        }
    }

    private fun skipTo(end: String) {
        while (i < text.length && !text.startsWith(end, i)) advance()
    }

    private fun skipBlockComment() {
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    advance(2)
                }
                text.startsWith("*/", i) -> {
                    depth--
                    advance(2)
                    if (depth == 0) return
                }
                else -> advance()
            }
        }
    }

    private fun skipCharLiteral() {
        advance()
        while (i < text.length && text[i] != '\'') advance(if (text[i] == '\\') 2 else 1)
        advance()
    }

    private fun name(): Token {
        val start = i
        val at = line
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) advance()
        return Token.Name(text.substring(start, i), at)
    }

    private fun quotedName(): Token {
        val at = line
        advance()
        val start = i
        skipTo("`")
        val name = text.substring(start, i)
        advance()
        return Token.Name(name, at)
    }

    private fun rawString(): Token {
        val at = line
        advance(3)
        val parts = Parts()
        while (i < text.length) {
            if (text.startsWith("\"\"\"", i)) {
                // The string ends at the last three quotes of a run; the ones before are its text.
                var run = 0
                while (i + run < text.length && text[i + run] == '"') run++
                parts.text("\"".repeat(run - 3))
                advance(run)
                break
            }
            if (!template(parts)) {
                parts.text(text[i].toString())
                advance()
            }
        }
        return Token.Str(parts.done(), at)
    }

    private fun plainString(): Token {
        val at = line
        advance()
        val parts = Parts()
        while (i < text.length && text[i] != '"' && text[i] != '\n') {
            if (text[i] == '\\' && i + 1 < text.length) {
                parts.text(escaped(text[i + 1]))
                advance(2)
            } else if (!template(parts)) {
                parts.text(text[i].toString())
                advance()
            }
        }
        advance()
        return Token.Str(parts.done(), at)
    }

    // A plain string's escape `\<c>`; a `\uXXXX` reads its four digits too.
    private fun escaped(c: Char): String =
        when (c) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            'b' -> "\b"
            'u' -> {
                val digits = text.substring(i + 2, minOf(i + 6, text.length))
                advance(4)
                digits.toIntOrNull(16)?.toChar()?.toString() ?: ""
            }
            else -> c.toString()
        }

    // To the `}` that closes a `${…}` template, past the strings, characters and comments in it,
    // whose braces are not the template's.
    private fun skipTemplateExpression() {
        var depth = 1
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> skipTo("\n")
                text.startsWith("/*", i) -> skipBlockComment()
                text.startsWith("\"\"\"", i) -> rawString()
                text[i] == '"' -> plainString()
                text[i] == '\'' -> skipCharLiteral()
                text[i] == '{' -> {
                    depth++
                    advance()
                }
                text[i] == '}' -> {
                    depth--
                    if (depth == 0) return
                    advance()
                }
                else -> advance()
            }
        }
    }

    // A template at the current position, `$name` or `${…}`, added to [parts]; false if none.
    private fun template(parts: Parts): Boolean {
        if (text[i] != '$' || i + 1 >= text.length) return false
        val next = text[i + 1]
        return when {
            next == '{' -> {
                advance(2)
                val start = i
                skipTemplateExpression()
                parts.ref(text.substring(start, i))
                advance()
                true
            }
            next.isLetter() || next == '_' -> {
                advance()
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) advance()
                parts.ref(text.substring(start, i))
                true
            }
            else -> false
        }
    }
}

private class Parts {
    private val parts = mutableListOf<Part>()
    private val pending = StringBuilder()

    fun text(text: String) {
        pending.append(text)
    }

    fun ref(source: String) {
        flush()
        parts += Part.Ref(source)
    }

    fun done(): List<Part> {
        flush()
        return parts
    }

    private fun flush() {
        if (pending.isNotEmpty()) parts += Part.Text(pending.toString())
        pending.clear()
    }
}

private val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")
private const val BRACED_ESCAPES = "pPxN"
private val COLLECTIONS = setOf("listOf", "setOf", "arrayOf", "sequenceOf", "mapOf")
private val MAPPINGS = setOf("map", "mapValues", "mapTo", "mapNotNull", "associateWith")

// Each regex [tokens] write, as its line and the tokens of its expression.
private fun regexesIn(tokens: List<Token>): List<Pair<Int, List<Token>>> {
    val found = mutableListOf<Pair<Int, List<Token>>>()
    for (i in tokens.indices) {
        val token = tokens[i]
        when {
            token.isName("Regex") && tokens.getOrNull(i + 1).isPunct('(') ->
                regexCall(tokens, i + 1)?.let(found::add)
            token.isName("Pattern") && tokens.getOrNull(i + 1).isPunct('.') &&
                tokens.getOrNull(i + 2).isName("compile") && tokens.getOrNull(i + 3).isPunct('(') ->
                regexCall(tokens, i + 3)?.let(found::add)
            token.isName("toRegex") && tokens.getOrNull(i - 1).isPunct('.') -> {
                val receiver = tokens.getOrNull(i - 2)
                // `toRegex(RegexOption.LITERAL)` and `toRegex(setOf(…, LITERAL))` take the text as it is.
                val literal =
                    tokens.getOrNull(i + 1).isPunct('(') &&
                        tokens.subList(i + 2, closing(tokens, i + 1)).any { it.isName("LITERAL") }
                if (!literal && receiver != null && (receiver is Token.Str || receiver is Token.Name)) {
                    found += receiver.line to listOf(receiver)
                }
            }
            token is Token.Name && token.text in COLLECTIONS && tokens.getOrNull(i + 1).isPunct('(') ->
                found += mappedToRegexes(tokens, i + 1)
        }
    }
    return found
}

// The first argument of the call whose `(` is at [open], unless the call is literal.
private fun regexCall(
    tokens: List<Token>,
    open: Int,
): Pair<Int, List<Token>>? {
    val close = closing(tokens, open)
    val arguments = tokens.subList(open + 1, close)
    if (arguments.any { it.isName("LITERAL") }) return null
    val first = mutableListOf<Token>()
    var depth = 0
    for (token in arguments) {
        if (depth == 0 && token.isPunct(',')) break
        when {
            token is Token.Punct && token.char in OPENERS -> depth++
            token is Token.Punct && token.char in CLOSERS -> depth--
            depth == 0 -> first += token
        }
    }
    return if (first.isEmpty()) null else first.first().line to first
}

// The elements of the collection call whose `(` is at [open], when the call is mapped with a
// lambda that calls `Regex(`: each run of literals and constants joined by `+`.
private fun mappedToRegexes(
    tokens: List<Token>,
    open: Int,
): List<Pair<Int, List<Token>>> {
    val close = closing(tokens, open)
    val mapped =
        tokens.getOrNull(close + 1).isPunct('.') &&
            tokens.getOrNull(close + 2).let { it is Token.Name && it.text in MAPPINGS } &&
            tokens.getOrNull(close + 3).isPunct('{')
    if (!mapped) return emptyList()
    val lambda = tokens.subList(close + 3, closing(tokens, close + 3))
    // A `Regex(…, RegexOption.LITERAL)` in the lambda takes the elements as they are.
    val callsRegex =
        lambda.indices.any {
            lambda[it].isName("Regex") && lambda.getOrNull(it + 1).isPunct('(') &&
                lambda.subList(it + 2, closing(lambda, it + 1)).none { token -> token.isName("LITERAL") }
        }
    if (!callsRegex) return emptyList()
    val runs = mutableListOf<MutableList<Token>>()
    var previous: Token? = null
    for (token in tokens.subList(open + 1, close)) {
        val operand = token is Token.Str || token is Token.Name
        when {
            // A map's key is not a pattern.
            token.isName("to") -> if (runs.isNotEmpty()) runs.removeAt(runs.lastIndex)
            operand && (previous.isPunct('+') || previous.isPunct('.')) && runs.isNotEmpty() -> runs.last() += token
            operand -> runs += mutableListOf(token)
        }
        previous = token
    }
    return runs.map { it.first().line to it }
}

// The index of the bracket that closes the one at [open], or the last token's.
private fun closing(
    tokens: List<Token>,
    open: Int,
): Int {
    var depth = 0
    for (i in open until tokens.size) {
        val token = tokens[i]
        if (token is Token.Punct && token.char in OPENERS) depth++
        if (token is Token.Punct && token.char in CLOSERS) depth--
        if (depth == 0) return i
    }
    return tokens.lastIndex
}

private const val OPENERS = "([{"
private const val CLOSERS = ")]}"

// Each `const val NAME = …` in [tokens], to the tokens of its value.
private fun constantsIn(tokens: List<Token>): Map<String, List<Token>> {
    val found = mutableMapOf<String, List<Token>>()
    for (i in tokens.indices) {
        if (!tokens[i].isName("const") || !tokens.getOrNull(i + 1).isName("val")) continue
        val name = tokens.getOrNull(i + 2) as? Token.Name ?: continue
        var j = i + 3
        if (tokens.getOrNull(j).isPunct(':')) j += 2
        if (!tokens.getOrNull(j).isPunct('=')) continue
        val value = mutableListOf<Token>()
        j++
        while (j < tokens.size) {
            val token = tokens[j]
            val operand = token is Token.Str || token is Token.Name
            val joins = value.isNotEmpty() && (tokens[j - 1].isPunct('+') || tokens[j - 1].isPunct('.'))
            when {
                operand && (value.isEmpty() || joins) -> value += token
                (token.isPunct('+') || token.isPunct('.')) && value.isNotEmpty() -> {}
                else -> break
            }
            j++
        }
        found[name.text] = value
    }
    return found
}

private fun Token?.isPunct(char: Char): Boolean = this is Token.Punct && this.char == char

private fun Token?.isName(name: String): Boolean = this is Token.Name && text == name
