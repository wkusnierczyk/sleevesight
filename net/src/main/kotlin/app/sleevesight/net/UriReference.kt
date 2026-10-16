package app.sleevesight.net

/**
 * Reference resolution (RFC 3986, 5.2), strict, as a pure function: [reference] against the absolute
 * URI [base]. Ktor's `takeFrom` keeps the base's query, fragment and user info where the RFC
 * replaces them, so redirects are resolved here. Null if [base] has no scheme.
 */
internal fun resolveReference(
    base: String,
    reference: String,
): String? {
    val b = UriParts.parse(base)
    val r = UriParts.parse(reference)
    if (b.scheme == null) return null
    val target =
        when {
            r.scheme != null -> {
                r.copy(path = removeDotSegments(r.path))
            }

            r.authority != null -> {
                r.copy(scheme = b.scheme, path = removeDotSegments(r.path))
            }

            r.path.isEmpty() -> {
                r.copy(scheme = b.scheme, authority = b.authority, path = b.path, query = r.query ?: b.query)
            }

            else -> {
                val path = if (r.path.startsWith("/")) r.path else merge(b, r.path)
                r.copy(scheme = b.scheme, authority = b.authority, path = removeDotSegments(path))
            }
        }
    return target.toString()
}

/** The five components of a URI reference (RFC 3986, appendix B); null where absent. */
internal data class UriParts(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** Recomposition (RFC 3986, 5.3). */
    override fun toString(): String =
        buildString {
            scheme?.let { append(it).append(':') }
            authority?.let { append("//").append(it) }
            append(path)
            query?.let { append('?').append(it) }
            fragment?.let { append('#').append(it) }
        }

    companion object {
        private val PATTERN =
            Regex(
                "^((?<scheme>[^:/?#]+):)?(//(?<authority>[^/?#]*))?(?<path>[^?#]*)" +
                    "(\\?(?<query>[^#]*))?(#(?<fragment>.*))?",
            )

        fun parse(text: String): UriParts {
            val match = checkNotNull(PATTERN.find(text)) { "every string matches" }

            fun part(name: String) = match.groups[name]?.value
            return UriParts(part("scheme"), part("authority"), part("path").orEmpty(), part("query"), part("fragment"))
        }
    }
}

/** Merging a relative path with the base's (RFC 3986, 5.2.3). */
private fun merge(
    base: UriParts,
    path: String,
): String =
    if (base.authority != null && base.path.isEmpty()) {
        "/$path"
    } else {
        // All but the last segment of the base path; none at all if it has no "/" (`urn:foo`).
        base.path.substring(0, base.path.lastIndexOf('/') + 1) + path
    }

/** Removing `.` and `..` segments (RFC 3986, 5.2.4). */
internal fun removeDotSegments(path: String): String {
    var input = path
    // Each segment with the "/" before it, so removing the last also removes that "/".
    val output = mutableListOf<String>()
    while (input.isNotEmpty()) input = removeDotStep(input, output)
    return output.joinToString("")
}

/** One step of the loop in 5.2.4: what remains of [input], with [output] updated. */
private fun removeDotStep(
    input: String,
    output: MutableList<String>,
): String =
    when {
        input.startsWith("../") -> {
            input.removePrefix("../")
        }

        input.startsWith("./") -> {
            input.removePrefix("./")
        }

        input.startsWith("/./") || input == "/." -> {
            "/" + input.removePrefix("/.").removePrefix("/")
        }

        input.startsWith("/../") || input == "/.." -> {
            output.removeLastOrNull()
            "/" + input.removePrefix("/..").removePrefix("/")
        }

        input == "." || input == ".." -> {
            ""
        }

        else -> {
            val end = input.indexOf('/', 1).let { if (it < 0) input.length else it }
            output += input.substring(0, end)
            input.substring(end)
        }
    }
