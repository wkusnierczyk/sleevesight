package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The words for `keys-qr` are read from the terminal, not echoed, when there is one. From Java 22,
    // `System.console()` is there even when the streams are not a terminal, and says so through
    // `isTerminal`, which earlier versions lack (and return no console then).
    val console =
        System.console()?.takeIf { console ->
            runCatching { console.javaClass.getMethod("isTerminal").invoke(console) as Boolean }.getOrDefault(true)
        }
    val terminal = console?.let { { prompt: String -> it.readPassword("%s", prompt)?.let(::String) } }
    val status = Cli(System.out, System.err, terminal = terminal).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
