package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The words for `keys-qr` are read from the terminal, not echoed, when there is one.
    val console = System.console()
    val terminal = console?.let { { prompt: String -> it.readPassword("%s", prompt)?.let(::String) } }
    val status = Cli(System.out, System.err, terminal = terminal).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
