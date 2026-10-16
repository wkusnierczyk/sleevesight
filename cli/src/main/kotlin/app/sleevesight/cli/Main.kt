package app.sleevesight.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val status = Cli(System.out, System.err).run(args.toList())
    System.out.flush()
    exitProcess(status)
}
