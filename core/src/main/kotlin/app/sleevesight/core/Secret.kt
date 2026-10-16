package app.sleevesight.core

/**
 * An API key or token. [toString] is redacted, so a secret printed by accident, in a log line, a
 * string template or the `toString()` of a class holding it, shows no part of its value. The value
 * is read only through [reveal], which keeps every use of it easy to find.
 *
 * Not a data class, and without `equals`, so it cannot be compared or printed by generated code.
 */
class Secret(
    private val value: String,
) {
    /** The value itself, for the one place that sends it: a request header or parameter. */
    fun reveal(): String = value

    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED: String = "Secret(<redacted>)"
    }
}
