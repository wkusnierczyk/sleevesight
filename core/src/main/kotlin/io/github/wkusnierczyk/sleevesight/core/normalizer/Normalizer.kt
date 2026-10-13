package io.github.wkusnierczyk.sleevesight.core.normalizer

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying

/**
 * Turns a player's title and artist strings into the song's plain title, its credited artists
 * and a lookup key, keeping what it removed (`design/design.md`, *Normalizer*).
 *
 * A port of the simulator's `normalize`, rule for rule, with one addition: the design's table
 * says a `(feat. X)` in the title moves X to the artists, and the simulator only noted it; here
 * X is added after the credited artists unless it is one of them already. On the fixture
 * playlist the result is the same, since its one featured artist is credited too.
 *
 * The patterns spell out JavaScript's `\s`, `\w`, `\d` and `\b`, and trimming uses JavaScript's
 * whitespace, so the port matches the simulator exactly, and every platform's regex engine
 * (Android's is ICU, where `\w` and `\s` are Unicode-aware) reads them the same way.
 */
class Normalizer(
    private val accentFolder: AccentFolder,
) {
    fun normalize(nowPlaying: NowPlaying): NormalizedTrack = normalize(nowPlaying.title, nowPlaying.artists)

    fun normalize(
        title: String,
        artists: String,
    ): NormalizedTrack {
        val notes = mutableListOf<String>()
        var t = title.trimJs()
        var from: String? = null
        var variant: String? = null
        val credited = splitArtists(artists).toMutableList()

        SUFFIX.find(t)?.let { m ->
            notes += "drop \"${m.groupValues[1]}\""
            t = t.substring(0, m.range.first)
        }
        FEATURING.find(t)?.let { m ->
            notes += "feat. → artists"
            t = t.replaceFirst(m.value, "")
            for (name in splitArtists(m.groupValues[1]).map { it.trimJs() }.filter { it.isNotEmpty() }) {
                if (credited.none { it.equals(name, ignoreCase = true) }) credited += name
            }
        }
        (FROM_PARENTHESIZED.find(t) ?: FROM_SUFFIX.find(t))?.let { m ->
            from = m.groupValues[1]
            notes += "from: ${m.groupValues[1]}"
            t = t.replaceFirst(m.value, "")
        }
        VARIANT.find(t)?.let { m ->
            variant = m.groupValues[1]
            notes += "variant: ${m.groupValues[1]}"
            t = t.replaceFirst(m.value, "")
        }

        val primary = credited.firstOrNull().orEmpty()
        return NormalizedTrack(
            title = t.trimJs(),
            artists = credited,
            primary = primary,
            from = from,
            variant = variant,
            notes = notes,
            lookupKey = lookupKey(primary, t),
        )
    }

    // Lowercase, decompose, drop the combining marks, keep ASCII letters, digits and the `|`.
    private fun lookupKey(
        primary: String,
        title: String,
    ): String {
        val decomposed = accentFolder.decompose("$primary|$title".lowercase())
        return decomposed.replace(COMBINING_MARKS, "").replace(NOT_KEY, " ").trim()
    }

    private fun splitArtists(artists: String): List<String> = artists.split(ARTIST_SEPARATOR).filter { it.isNotEmpty() }

    private companion object {
        // JavaScript's `\s`: its WhiteSpace and LineTerminator characters.
        const val S = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

        val SUFFIX =
            Regex(
                "$S+-$S+((?:[0-9]{4}$S+)?remaster(?:ed)?(?:$S+[0-9]{4})?|demo|radio edit|single edit|live|mono|" +
                    // The simulator's `[\w' ]*\bversion`: the word boundary means the `[\w' ]*` part is
                    // empty (preceded by whitespace) or ends with a quote or a space.
                    "(?:[A-Za-z0-9_' ]*[' ])?version)$",
                RegexOption.IGNORE_CASE,
            )
        val FEATURING = Regex("$S*\\((?:feat\\.|ft\\.)$S*([^)]*)\\)", RegexOption.IGNORE_CASE)
        val FROM_PARENTHESIZED = Regex("$S*\\(from ([^)]*)\\)", RegexOption.IGNORE_CASE)
        val FROM_SUFFIX = Regex("$S+-$S+from$S+\"?([^\"]+)\"?$", RegexOption.IGNORE_CASE)
        val VARIANT = Regex("$S*\\(([^)]*(?:piano|acoustic|cover|strange game)[^)]*)\\)", RegexOption.IGNORE_CASE)
        val ARTIST_SEPARATOR = Regex("$S*,$S*")
        val COMBINING_MARKS = Regex("[\\u0300-\\u036F]")
        val NOT_KEY = Regex("[^a-z0-9|]+")

        val JS_SPACE = Regex("^$S+|$S+$")

        fun String.trimJs(): String = replace(JS_SPACE, "")
    }
}
