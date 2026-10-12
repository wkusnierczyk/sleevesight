package sleevesight.checks

import java.io.File
import java.util.Properties

/** The project paths of the modules allowed to reach an HTTP client (`network-modules`). */
fun networkModules(rules: File): Set<String> =
    Properties()
        .apply { rules.inputStream().use { load(it) } }
        .getProperty("network-modules", "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { ":$it" }
        .toSet()
