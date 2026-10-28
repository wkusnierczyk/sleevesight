package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The words for `keys-qr` are typed at Java's console, when it is a terminal ([Terminal.of]).
    val status = Cli(System.out, System.err, terminal = Terminal::of).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
