package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // The words for `keys-qr` are read from the terminal with its echo off ([Terminal.of]).
    val status = Cli(System.out, System.err, terminal = { Terminal.of(System.err) }).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
