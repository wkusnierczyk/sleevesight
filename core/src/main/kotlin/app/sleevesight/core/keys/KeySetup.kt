package app.sleevesight.core.keys

import app.sleevesight.core.Secret

// The keys a QR code carries (#268): one key alone, for its own field, or a setup code with
// several, named, which the CLI draws (`keys-qr`) and the settings screen reads (*Scan keys*).

/** A service whose key a setup code may carry, by the name the code gives it. */
enum class KeyName(
    val code: String,
) {
    ANTHROPIC("anthropic"),
    OPENAI("openai"),
    GOOGLE("google"),
    EXA("exa"),
    LASTFM("lastfm"),
    ;

    companion object {
        /** The service a setup code names [code], or null for a name this version does not know. */
        fun of(code: String): KeyName? = entries.firstOrNull { it.code == code }
    }
}

/** Why a key read from a code is refused, so the user is told rather than a wrong key saved. */
enum class KeyProblem {
    /** Nothing after the name. */
    EMPTY,

    /** A space, a line break or a character no key of the service has (a dot only Google's may). */
    BAD_CHARACTERS,

    /**
     * Not the service's shape: Anthropic's starts `sk-ant-`, OpenAI's `sk-` (and not `sk-ant-`),
     * Google's `AIza` or `AQ.`, Last.fm's is 32 hexadecimal digits, Exa's 16 to 200 characters.
     */
    WRONG_SHAPE,

    /** The name given twice, so which key is meant cannot be told. */
    DUPLICATE,
}

/**
 * The shape each service's key has, checked on a key read from a code, not on one typed. A check
 * is loose where the service does not document its keys' form: Exa's are only checked for their
 * characters and a plausible length, so a key of a form Exa adopts later still passes. OpenAI's
 * keys start `sk-` whatever their kind (a project's `sk-proj-`, a service account's `sk-svcacct-`),
 * so only that and a plausible length are checked; a key that starts `sk-ant-` is Anthropic's,
 * and refused as OpenAI's. Google's start `AIza`, or `AQ.` for the newer authentication keys AI
 * Studio makes, which hold a dot; only that and a plausible length are checked, so a later key of
 * either kind still passes.
 */
object KeyShape {
    private val TOKEN = Regex("[A-Za-z0-9_-]+")
    private val GOOGLE_TOKEN = Regex("[A-Za-z0-9._-]+")
    private val HEX_32 = Regex("[0-9a-fA-F]{32}")
    private const val ANTHROPIC_PREFIX = "sk-ant-"
    private const val ANTHROPIC_SHORTEST = 40
    private const val ANTHROPIC_LONGEST = 400
    private const val OPENAI_PREFIX = "sk-"
    private const val OPENAI_SHORTEST = 40
    private const val OPENAI_LONGEST = 400
    private val GOOGLE_PREFIXES = listOf("AIza", "AQ.")
    private const val GOOGLE_SHORTEST = 30
    private const val GOOGLE_LONGEST = 400
    private const val EXA_SHORTEST = 16
    private const val EXA_LONGEST = 200

    /** What is wrong with [value] as [name]'s key, or null when it has the key's shape. */
    fun check(
        name: KeyName,
        value: String,
    ): KeyProblem? =
        when {
            value.isEmpty() -> {
                KeyProblem.EMPTY
            }

            !(if (name == KeyName.GOOGLE) GOOGLE_TOKEN else TOKEN).matches(value) -> {
                KeyProblem.BAD_CHARACTERS
            }

            else -> {
                val shaped =
                    when (name) {
                        KeyName.ANTHROPIC ->
                            value.startsWith(ANTHROPIC_PREFIX) &&
                                value.length in ANTHROPIC_SHORTEST..ANTHROPIC_LONGEST
                        KeyName.OPENAI ->
                            value.startsWith(OPENAI_PREFIX) &&
                                !value.startsWith(ANTHROPIC_PREFIX) &&
                                value.length in OPENAI_SHORTEST..OPENAI_LONGEST
                        KeyName.GOOGLE ->
                            GOOGLE_PREFIXES.any { value.startsWith(it) } &&
                                value.length in GOOGLE_SHORTEST..GOOGLE_LONGEST
                        KeyName.EXA -> value.length in EXA_SHORTEST..EXA_LONGEST
                        KeyName.LASTFM -> HEX_32.matches(value)
                    }
                KeyProblem.WRONG_SHAPE.takeUnless { shaped }
            }
        }
}

/** What a scanned code holds, read as a setup code ([KeySetup.read]). */
sealed interface SetupRead {
    /** Not a setup code: no `sleevesight-keys:` first line. */
    data object NotASetup : SetupRead

    /** A setup code of a [version] this app cannot read: a later one, from a newer CLI. */
    data class Unsupported(
        val version: String,
    ) : SetupRead

    /**
     * A setup code: the [keys] that have their service's shape, in the code's order, the ones
     * [refused] and why, the [unknown] names (a later version's services), and how many lines could
     * not be read as `name=value` ([unreadable]). Only [keys] are ever saved; no value of the others
     * is kept, so none can be shown.
     */
    class Setup(
        val keys: Map<KeyName, Secret>,
        val refused: Map<KeyName, KeyProblem>,
        val unknown: List<String>,
        val unreadable: Int,
    ) : SetupRead
}

/**
 * The setup code's text, version 1: the line `sleevesight-keys:v1`, then one `name=value` line per
 * key, names from [KeyName]. Spaces around a name or a value and blank lines are dropped; a line
 * break may be `\n` or `\r\n`. Later versions keep the first line's form, so an older app can tell
 * it is too old; a service added later (#227, #228, #229) is a new name in the same version, which
 * an older app reports as unknown.
 */
object KeySetup {
    const val PREFIX = "sleevesight-keys:"
    const val VERSION = "v1"

    /** The longest unknown name reported as given; a longer one is cut, in case it is a key itself. */
    private const val SHOWN_NAME = 24

    /** Reads [text], a scanned code, as a setup code. */
    fun read(text: String): SetupRead {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull()
        val version = first?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.trim()
        return when (version) {
            null -> SetupRead.NotASetup
            VERSION -> setup(lines.drop(1))
            else -> SetupRead.Unsupported(version.take(SHOWN_NAME))
        }
    }

    // The `name=value` [lines] after the first.
    private fun setup(lines: List<String>): SetupRead.Setup {
        val entries =
            lines.map {
                it.substringBefore('=', missingDelimiterValue = "").trim() to
                    it.substringAfter('=').trim()
            }
        val named = entries.filter { it.first.isNotEmpty() }
        val known = named.mapNotNull { (name, value) -> KeyName.of(name)?.let { it to value } }
        val counts = known.groupingBy { it.first }.eachCount()
        val keys = linkedMapOf<KeyName, Secret>()
        val refused = linkedMapOf<KeyName, KeyProblem>()
        for ((name, value) in known.distinctBy { it.first }) {
            when (val problem = if (counts.getValue(name) > 1) KeyProblem.DUPLICATE else KeyShape.check(name, value)) {
                null -> keys[name] = Secret(value)
                else -> refused[name] = problem
            }
        }
        return SetupRead.Setup(
            keys = keys,
            refused = refused,
            unknown = named.filter { KeyName.of(it.first) == null }.map { it.first.take(SHOWN_NAME) },
            unreadable = entries.size - named.size,
        )
    }

    /** The setup code's text for [keys], in [KeyName]'s order. */
    fun write(keys: Map<KeyName, Secret>): String =
        buildString {
            append(PREFIX).append(VERSION)
            for (name in KeyName.entries) {
                val key = keys[name] ?: continue
                append('\n').append(name.code).append('=').append(key.reveal())
            }
        }

    /** Whether [text] looks like a setup code rather than one key: scanned into a key's own field. */
    fun isSetup(text: String): Boolean = text.trimStart().startsWith(PREFIX)
}
