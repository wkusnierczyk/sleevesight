package io.github.wkusnierczyk.sleevesight.core.normalizer

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying

/**
 * Turns a player's title and artist strings into the song's plain title, its credited artists
 * and a lookup key, keeping what it removed (`design/design.md`, *Normalizer*).
 *
 * A port of the simulator's `normalize`, rule for rule, with two deviations:
 * - The design's table says a `(feat. X)` in the title moves X to the artists, and the simulator
 *   only noted it; here X is added after the credited artists unless it is one of them already.
 *   On the fixture playlist the result is the same, since its one featured artist is credited.
 * - An empty artist string gives an empty [NormalizedTrack.primary] and a key starting `|`, where
 *   the simulator gave `undefined` and `undefined|`. With a `(feat. X)` in the title, X becomes the
 *   primary artist and goes into the key.
 *
 * The patterns spell out JavaScript's `\s`, `\w`, `\d` and `\b`, trimming uses JavaScript's
 * whitespace, and case is matched in ASCII only, as JavaScript's `/i` without `u` does (no
 * `IGNORE_CASE`, which on the JVM and in ICU also folds `ſ` to `s`, `ı` to `i` and the Kelvin
 * sign to `k`). So the port matches the simulator exactly, and every platform's regex engine
 * (Android's is ICU, where `\w` and `\s` are Unicode-aware) reads the patterns the same way.
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
                "$S+-$S+((?:[0-9]{4}$S+)?${ci("remaster")}(?:${ci("ed")})?(?:$S+[0-9]{4})?|" +
                    "${ci("demo")}|${ci("radio edit")}|${ci("single edit")}|${ci("live")}|${ci("mono")}|" +
                    // The simulator's `[\w' ]*\bversion`: the word boundary means the `[\w' ]*` part is
                    // empty (preceded by whitespace) or ends with a quote or a space.
                    "(?:[A-Za-z0-9_' ]*[' ])?${ci("version")})$",
            )
        val FEATURING = Regex("$S*\\((?:${ci("feat")}\\.|${ci("ft")}\\.)$S*([^)]*)\\)")
        val FROM_PARENTHESIZED = Regex("$S*\\(${ci("from")} ([^)]*)\\)")
        val FROM_SUFFIX = Regex("$S+-$S+${ci("from")}$S+\"?([^\"]+)\"?$")
        val VARIANT =
            Regex(
                "$S*\\(([^)]*(?:${ci("piano")}|${ci("acoustic")}|${ci("cover")}|${ci("strange game")})[^)]*)\\)",
            )
        val ARTIST_SEPARATOR = Regex("$S*,$S*")
        val COMBINING_MARKS = Regex("[\\u0300-\\u036F]")
        val NOT_KEY = Regex("[^a-z0-9|]+")

        val JS_SPACE = Regex("^$S+|$S+$")

        fun String.trimJs(): String = replace(JS_SPACE, "")

        // An ASCII letter matches either case, as in JavaScript's `/i` without `u`: `ci("mono")` is
        // `[mM][oO][nN][oO]`. Other characters stand for themselves.
        fun ci(word: String): String {
            require(word == word.lowercase()) { "ci takes a lowercase pattern, was $word" }
            return word.map { c -> if (c in 'a'..'z') "[$c${c.uppercaseChar()}]" else c.toString() }.joinToString("")
        }
    }
}
