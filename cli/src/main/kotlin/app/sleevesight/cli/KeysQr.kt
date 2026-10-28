package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyName
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyShape
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.platform.JvmKeyCrypto
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream

/**
 * `keys-qr` (#268): draws a setup code ([KeySetup]) in the terminal, for the app's *Load keys*,
 * sealed (#590, [SealedSetup]) with the five words *Load keys* shows, read from exactly two places:
 * Java's console, with `readPassword`, which masks them, and only when it is a terminal ([terminal]);
 * or the file `--words-from` names, `-` for standard input, where nothing is typed. Never from the
 * command line, where the shell's history would keep them. Otherwise nothing is read, and it says
 * so before reading anything: piping the output (`| cat`) needs `--words-from`. With `--unsealed`,
 * the clear code, which is the keys themselves.
 * The keys come from the developer's environment or keys file ([Keys]); with `--keys`, from that
 * file alone, not the environment, so a tester's file cannot pick up the developer's own keys; with
 * `--<name>-from`, only from the files given, `-` for standard input, so a tester's key is drawn without being saved
 * beside the developer's keys or typed on the command line, where the shell history would keep it.
 * Nothing is written to a file or sent anywhere, and no key is printed as text: the code is drawn
 * on [out], everything else said on [err].
 */
class KeysQr(
    private val out: PrintStream,
    private val err: PrintStream,
    private val environment: Map<String, String>,
    private val input: InputStream,
    private val terminal: () -> Terminal,
) {
    private var unsealed = false
    private var stdinTaken = false
    private var wordsFrom: String? = null
    private val shell by lazy(terminal)

    /** The words cannot be read without showing them: said, with nothing read. */
    private class WordsRefused : Exception(REFUSED)

    fun run(args: List<String>): Int {
        val keys =
            try {
                read(args)
            } catch (e: WordsRefused) {
                err.println(e.message)
                null
            } catch (e: IllegalArgumentException) {
                err.println(e.message)
                err.println(USAGE)
                null
            } catch (_: IOException) {
                // Neither the path nor the error's message, which names it: either may be a key.
                err.println("A key could not be read: a file given, the keys file or standard input.")
                null
            }
        return keys?.let(::drawSetup) ?: Cli.EXIT_USAGE
    }

    // Draws [keys]' setup code, unless one is refused or there are none.
    private fun drawSetup(keys: Map<KeyName, Secret>): Int {
        val refused = keys.mapNotNull { (name, key) -> KeyShape.check(name, key.reveal())?.let { name to it } }
        val problem =
            when {
                refused.isNotEmpty() -> {
                    refused.joinToString("\n") { (name, why) -> "Not drawn: the ${label(name)} key ${reason(why)}." }
                }

                keys.isEmpty() -> {
                    "No keys found: set ${Keys.ANTHROPIC}, ${Keys.OPENAI}, ${Keys.GOOGLE}, ${Keys.EXA} " +
                        "or ${Keys.LASTFM} (in a keys file, also the providers' usual names, " +
                        "such as ANTHROPIC_API_KEY), or give --<name>-from."
                }

                else -> {
                    null
                }
            }
        if (problem != null) err.println(problem)
        return when {
            problem != null -> Cli.EXIT_USAGE
            unsealed -> drawCode(keys, null)
            else -> WordsReader(err, input).read(wordsFrom, shell.console)?.let { drawCode(keys, it) } ?: Cli.EXIT_USAGE
        }
    }

    // Draws [keys]' setup code, sealed with [words], or clear without them.
    private fun drawCode(
        keys: Map<KeyName, Secret>,
        words: List<String>?,
    ): Int {
        val setup = KeySetup.write(keys)
        val code = words?.let { SealedSetup.seal(setup, it, JvmKeyCrypto) } ?: setup
        out.print(draw(encode(code)))
        out.flush()
        err.println("Holds the ${keys.keys.joinToString { label(it) }} key${if (keys.size > 1) "s" else ""}.")
        if (words == null) {
            err.println("In Sleevesight: Settings, Secrets, Load keys. Then clear the terminal: the code is the keys.")
        } else {
            err.println(
                "Sealed with the phone's words: it opens only with them. On the phone, Next, then scan it " +
                    "(the phone takes it once, within 5 minutes of showing the words). Then clear the terminal.",
            )
        }
        return 0
    }

    private fun read(args: List<String>): Map<KeyName, Secret> {
        val (from, given) = parse(args)
        check(from, given)
        if (from.isEmpty()) {
            val loaded = if (given == null) Keys.load(environment) else Keys.load(emptyMap(), given)
            return KeyName.entries.mapNotNull { name -> keyOf(loaded, name)?.let { name to it } }.toMap()
        }
        return from.mapValues { (_, path) -> Secret(readKey(path)) }
    }

    // The options in [args]: the files of the keys by service, and the keys file; `--unsealed` and
    // `--words-from` are kept as they are read.
    private fun parse(args: List<String>): Pair<Map<KeyName, String>, File?> {
        val from = mutableMapOf<KeyName, String>()
        var keysFile: File? = null
        val seen = mutableSetOf<String>()
        val rest = args.iterator()
        while (rest.hasNext()) {
            val arg = rest.next()
            val name = KeyName.entries.firstOrNull { arg == "--${it.code}-from" }
            // No argument's value is repeated: it may be a key, typed by mistake (`--anthropic-from=sk-…`).
            require(name != null || arg == "--keys" || arg == UNSEALED || arg == WORDS_FROM) {
                // Named only if it looks like an option name and nothing more.
                if (OPTION.matches(arg)) "Unknown option $arg" else "Unknown option or unexpected argument"
            }
            require(seen.add(arg)) { "$arg given twice" }
            require(arg == UNSEALED || rest.hasNext()) { "$arg needs a value" }
            when {
                arg == UNSEALED -> unsealed = true
                arg == WORDS_FROM -> wordsFrom = rest.next()
                name == null -> keysFile = File(rest.next())
                else -> from[name] = rest.next()
            }
        }
        return from to keysFile
    }

    // The options' rules; then, before any key is read, whether the words can be read at all.
    private fun check(
        from: Map<KeyName, String>,
        given: File?,
    ) {
        require(from.values.count { it == STDIN } <= 1) { "Only one key can be read from standard input" }
        stdinTaken = STDIN in from.values
        require(given == null || given.isFile) { "--keys: no such file" }
        require(given == null || from.isEmpty()) { "--keys and --<name>-from cannot be combined" }
        val words = wordsFrom
        require(words == null || !unsealed) { "$WORDS_FROM and $UNSEALED cannot be combined" }
        require(words != STDIN || !stdinTaken) {
            "Standard input carries a key: give the words in a file ($WORDS_FROM <file>), or type them in a terminal"
        }
        // The paths are not repeated: one may be a key or the words, given in its place.
        require(words == null || readable(words)) { "$WORDS_FROM: no such file" }
        for ((name, path) in from) require(readable(path)) { "--${name.code}-from: no such file" }
        if (!unsealed && shell.wordsSource(words != null) == Terminal.WordsSource.REFUSED) throw WordsRefused()
    }

    // The key in [path], or on standard input for `-`: its first line that is not blank, trimmed.
    private fun readKey(path: String): String {
        // The path was checked ([check]).
        val text = if (path == STDIN) input.bufferedReader().readText() else File(path).readText()
        return text
            .lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    companion object {
        private const val STDIN = "-"
        private const val QUIET_ZONE = 4
        private const val UNSEALED = "--unsealed"
        private const val WORDS_FROM = "--words-from"
        private const val REFUSED =
            "Type the words in a terminal (without redirecting the output), or pass --words-from FILE."

        /** What an option's name looks like, and nothing more: anything else may hold a key. */
        private val OPTION = Regex("--[a-z-]{1,30}")

        val USAGE: String =
            """
            Usage: sleevesight-cli keys-qr [--keys <file>] [--anthropic-from <file|->] [--openai-from <file|->]
                                           [--google-from <file|->] [--exa-from <file|->] [--lastfm-from <file|->]
                                           [--words-from <file|->] [--unsealed]

              Draws a setup code for the app's Settings, Secrets, Load keys: the keys from the
              environment or ~/.config/sleevesight/keys.env; with --keys, from that file alone; with
              any --<name>-from, only the keys in those files, - for standard input (one at most).
              --keys and --<name>-from cannot be combined.
              The code is sealed with the five words Load keys shows on the phone, typed at a prompt
              that does not show them (only in a terminal, its output not redirected), or read from
              --words-from <file|->, - for standard input (not when a key is read from it), never
              taken from the command line. Piping the output (| cat) needs --words-from. The code
              opens only with those words, and the phone takes it once, within 5 minutes of showing
              them. With --unsealed, the clear code, which is the keys themselves, for a key's own
              Scan QR code.
              Nothing is written to a file or sent anywhere. Do not make the code with an online QR
              generator: it would receive the keys.

              A keys file has NAME=value lines, and may name each key as Sleevesight does or as its
              provider usually does; of the names a file gives one key under, the first listed below wins.
              The environment is read by the SLEEVESIGHT_ names alone.
            """.trimIndent() +
                Keys.FILE_NAMES.values.joinToString("") {
                    "\n    " + it.joinToString(", ")
                }

        /** The QR code for [text], at error correction M (15 %), with the standard quiet zone. */
        fun encode(text: String): BitMatrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to QUIET_ZONE,
                ),
            )

        /**
         * [matrix] in half blocks, two modules a character, dark on white whatever the terminal's
         * colours: each line sets black on bright white and resets at its end.
         */
        fun draw(matrix: BitMatrix): String =
            buildString {
                for (y in 0 until matrix.height step 2) {
                    append(COLOURS)
                    for (x in 0 until matrix.width) {
                        val top = matrix[x, y]
                        val bottom = y + 1 < matrix.height && matrix[x, y + 1]
                        append(
                            when {
                                top && bottom -> '█'
                                top -> '▀'
                                bottom -> '▄'
                                else -> ' '
                            },
                        )
                    }
                    append(RESET).append('\n')
                }
            }

        private const val SHAPES =
            "Anthropic's start sk-ant-, OpenAI's sk- (not sk-ant-), Google's AIza or AQ., " +
                "Last.fm's are 32 hexadecimal digits, Exa's 16 to 200 characters"
        private const val COLOURS = "\u001b[30;107m"
        private const val RESET = "\u001b[0m"

        // The key [loaded] holds for [name].
        private fun keyOf(
            loaded: Keys,
            name: KeyName,
        ): Secret? =
            when (name) {
                KeyName.ANTHROPIC -> loaded.anthropic
                KeyName.OPENAI -> loaded.openAi
                KeyName.GOOGLE -> loaded.google
                KeyName.EXA -> loaded.exa
                KeyName.LASTFM -> loaded.lastFm
            }

        private fun label(name: KeyName): String =
            when (name) {
                KeyName.ANTHROPIC -> "Anthropic"
                KeyName.OPENAI -> "OpenAI"
                KeyName.GOOGLE -> "Google"
                KeyName.EXA -> "Exa"
                KeyName.LASTFM -> "Last.fm"
            }

        private fun reason(problem: KeyProblem): String =
            when (problem) {
                KeyProblem.EMPTY -> "is empty"
                KeyProblem.BAD_CHARACTERS -> "has a space or a character no such key has"
                KeyProblem.WRONG_SHAPE -> "does not look like one ($SHAPES)"
                KeyProblem.DUPLICATE -> "is given twice"
            }
    }
}

/**
 * Whether [path] is standard input (`-`) or a file to read: not only a regular one, since a shell's
 * `<(pbpaste)` is a pipe.
 */
internal fun readable(path: String): Boolean = path == "-" || File(path).let { it.exists() && !it.isDirectory }
