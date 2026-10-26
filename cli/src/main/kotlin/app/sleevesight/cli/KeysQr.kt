package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyName
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyShape
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
 * `keys-qr` (#268): draws a setup code ([KeySetup]) in the terminal, for the app's *Load keys*.
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
) {
    fun run(args: List<String>): Int {
        val keys =
            try {
                read(args)
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
        if (problem != null) {
            err.println(problem)
            return Cli.EXIT_USAGE
        }
        out.print(draw(encode(KeySetup.write(keys))))
        out.flush()
        err.println("Holds the ${keys.keys.joinToString { label(it) }} key${if (keys.size > 1) "s" else ""}.")
        err.println("In Sleevesight: Settings, Secrets, Load keys. Then clear the terminal: the code is the keys.")
        return 0
    }

    private fun read(args: List<String>): Map<KeyName, Secret> {
        val from = mutableMapOf<KeyName, String>()
        var keysFile: File? = null
        val rest = args.iterator()
        while (rest.hasNext()) {
            val arg = rest.next()
            val name = KeyName.entries.firstOrNull { arg == "--${it.code}-from" }
            // No argument's value is repeated: it may be a key, typed by mistake (`--anthropic-from=sk-…`).
            require(name != null || arg == "--keys") {
                // Named only if it looks like an option name and nothing more.
                if (OPTION.matches(arg)) "Unknown option $arg" else "Unknown option or unexpected argument"
            }
            require(if (name == null) keysFile == null else name !in from) { "$arg given twice" }
            require(rest.hasNext()) { "$arg needs a value" }
            val value = rest.next()
            if (name == null) keysFile = File(value) else from[name] = value
        }
        require(from.values.count { it == STDIN } <= 1) { "Only one key can be read from standard input" }
        val given = keysFile
        require(given == null || given.isFile) { "--keys: no such file" }
        require(given == null || from.isEmpty()) { "--keys and --<name>-from cannot be combined" }
        if (from.isEmpty()) {
            val loaded = if (given == null) Keys.load(environment) else Keys.load(emptyMap(), given)
            return KeyName.entries.mapNotNull { name -> keyOf(loaded, name)?.let { name to it } }.toMap()
        }
        return from.mapValues { (name, path) -> Secret(readKey(name, path)) }
    }

    // The key in [path], or on standard input for `-`: its first line that is not blank, trimmed.
    private fun readKey(
        name: KeyName,
        path: String,
    ): String {
        val text =
            if (path == STDIN) {
                input.bufferedReader().readText()
            } else {
                val file = File(path)
                // The path is not repeated either: it may be the key itself, given in its place.
                require(file.isFile) { "--${name.code}-from: no such file" }
                file.readText()
            }
        return text
            .lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    companion object {
        private const val STDIN = "-"
        private const val QUIET_ZONE = 4

        /** What an option's name looks like, and nothing more: anything else may hold a key. */
        private val OPTION = Regex("--[a-z-]{1,30}")

        val USAGE: String =
            """
            Usage: sleevesight-cli keys-qr [--keys <file>] [--anthropic-from <file|->] [--openai-from <file|->]
                                           [--google-from <file|->] [--exa-from <file|->] [--lastfm-from <file|->]

              Draws a setup code for the app's Settings, Secrets, Load keys: the keys from the
              environment or ~/.config/sleevesight/keys.env; with --keys, from that file alone; with
              any --<name>-from, only the keys in those files, - for standard input (one at most).
              --keys and --<name>-from cannot be combined.
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
