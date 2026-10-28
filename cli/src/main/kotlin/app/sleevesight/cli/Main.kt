package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Java's own console, whose `readPassword` never echoes, before anything loads one: JLine's, the
    // default from Java 22, echoes under `TERM=dumb` (#590). The start scripts pass it too.
    System.setProperty("jdk.console", "java.base")
    // The words for `keys-qr` are typed at Java's console, when it is a terminal ([Terminal.of]).
    val status = Cli(System.out, System.err, terminal = Terminal::of).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
