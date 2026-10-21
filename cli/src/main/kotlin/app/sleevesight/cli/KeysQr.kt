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
import java.io.InputStream
import java.io.PrintStream

/**
 * `keys-qr` (#268): draws a setup code ([KeySetup]) in the terminal, for the app's *Scan keys*.
 * The keys come from the developer's environment or keys file ([Keys]), or, with `--<name>-from`,
 * only from the files given, `-` for standard input, so a tester's key is drawn without being saved
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
                    "No keys found: set ${Keys.ANTHROPIC}, ${Keys.EXA} or ${Keys.LASTFM}, or give --<name>-from."
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
        err.println("In Sleevesight: Settings, API keys, Scan keys. Then clear the terminal: the code is the keys.")
        return 0
    }

    private fun read(args: List<String>): Map<KeyName, Secret> {
        val from = mutableMapOf<KeyName, String>()
        var keysFile = Keys.DEFAULT_FILE
        val rest = args.iterator()
        while (rest.hasNext()) {
            val arg = rest.next()
            val name = KeyName.entries.firstOrNull { arg == "--${it.code}-from" }
            require(name != null || arg == "--keys") { "Unknown option $arg" }
            require(rest.hasNext()) { "$arg needs a value" }
            val value = rest.next()
            if (name == null) keysFile = File(value) else from[name] = value
        }
        require(from.values.count { it == STDIN } <= 1) { "Only one key can be read from standard input" }
        if (from.isEmpty()) {
            val loaded = Keys.load(environment, keysFile)
            return listOfNotNull(
                loaded.anthropic?.let { KeyName.ANTHROPIC to it },
                loaded.exa?.let { KeyName.EXA to it },
                loaded.lastFm?.let { KeyName.LASTFM to it },
            ).toMap()
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
                require(file.isFile) { "--${name.code}-from: no such file $path" }
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

        val USAGE: String =
            """
            Usage: sleevesight-cli keys-qr [--keys <file>] [--anthropic-from <file|->] [--exa-from <file|->]
                                           [--lastfm-from <file|->]

              Draws a setup code for the app's Settings, API keys, Scan keys: the keys from the
              environment or the keys file (default: ~/.config/sleevesight/keys.env), or, with any
              --<name>-from, only the keys in those files, - for standard input (one at most).
              Nothing is written to a file or sent anywhere. Do not make the code with an online QR
              generator: it would receive the keys.
            """.trimIndent()

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

        private const val SHAPES = "Anthropic's start sk-ant-, Last.fm's are 32 hexadecimal digits"
        private const val COLOURS = "\u001b[30;107m"
        private const val RESET = "\u001b[0m"

        private fun label(name: KeyName): String =
            when (name) {
                KeyName.ANTHROPIC -> "Anthropic"
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
