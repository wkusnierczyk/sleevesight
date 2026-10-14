package io.github.wkusnierczyk.sleevesight.cli

import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetError
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.net.NetResponse
import io.github.wkusnierczyk.sleevesight.net.NetResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * The recorded HTTP responses in `fixtures/http/` (`fixtures/http/README.md`): one directory per
 * source, each with an `index.json` of its requests (method, URL, query, status, file), the format
 * `ReplayNetClient` in `:sources`' test fixtures reads. A `POST` (Anthropic's messages) is matched on
 * its body too, by the body's SHA-256 (`bodySha256`, in the `anthropic` index only).
 *
 * Only the keyless sources and Anthropic are recorded. Exa's results may not be stored (its terms,
 * §4.2(a); `design/design.md`, *Web search provider (D11)*), so web search is neither recorded nor
 * replayed; Last.fm has no key yet.
 */
class FixtureStore(
    private val root: File,
) {
    private val indexes = mutableMapOf<String, MutableList<Entry>>()
    private val changed = mutableSetOf<String>()

    /** One recorded exchange. */
    data class Entry(
        val method: String,
        val url: String,
        val query: Map<String, String>,
        val status: Int,
        val file: String,
        val bodySha256: String? = null,
    ) {
        fun matches(request: NetRequest): Boolean =
            method == request.method.name &&
                url == request.url &&
                query == request.query &&
                bodySha256 == request.body?.let { sha256(it.text) }
    }

    /** Whether [source] (a directory) has any recording. */
    fun hasRecordings(source: String): Boolean = index(source).isNotEmpty()

    /** The recorded response to [request], or null. */
    fun find(request: NetRequest): Pair<Entry, File>? =
        sourceOf(request.url)?.let { source ->
            index(source).firstOrNull { it.matches(request) }?.let { entry ->
                entry to
                    File(File(root, source), entry.file)
            }
        }

    /**
     * Records [body], the response to [request] with [status], replacing an earlier recording of the
     * same request; [extension] is the file's (`json`, `sse`). Written to disk by [save].
     */
    fun record(
        request: NetRequest,
        status: Int,
        body: String,
        extension: String,
    ) {
        val source = requireNotNull(sourceOf(request.url)) { "Not a recorded source: ${hostOf(request.url)}" }
        val index = index(source)
        val directory = File(root, source).apply { mkdirs() }
        index.filter { it.matches(request) }.forEach { File(directory, it.file).delete() }
        index.removeAll { it.matches(request) }
        val hash = request.body?.let { sha256(it.text) }
        val base = if (hash != null) "messages-${hash.take(HASH_PREFIX)}" else nameOf(request)
        val taken = index.map { it.file }.toSet()
        val name =
            generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) "$base.$extension" else "$base-$n.$extension" }
                .first { it !in taken }
        File(directory, name).writeText(body)
        request.body?.let {
            File(
                directory,
                name.substringBeforeLast('.') + ".request.json",
            ).writeText(pretty(it.text))
        }
        index += Entry(request.method.name, request.url, request.query, status, name, hash)
        changed += source
    }

    /** Writes every index recorded into since loading, sorted by file, as the Python recorder writes them. */
    fun save() {
        for (source in changed) {
            val index = index(source)
            index.sortBy { it.file }
            val json =
                JsonArray(
                    index.map { entry ->
                        JsonObject(
                            buildMap {
                                put("method", JsonPrimitive(entry.method))
                                put("url", JsonPrimitive(entry.url))
                                put("query", JsonObject(entry.query.mapValues { JsonPrimitive(it.value) }))
                                put("status", JsonPrimitive(entry.status))
                                put("file", JsonPrimitive(entry.file))
                                entry.bodySha256?.let { put("bodySha256", JsonPrimitive(it)) }
                            },
                        )
                    },
                )
            File(File(root, source), INDEX).writeText(PRETTY.encodeToString(JsonElement.serializer(), json) + "\n")
        }
    }

    private fun index(source: String): MutableList<Entry> =
        indexes.getOrPut(source) {
            val file = File(File(root, source), INDEX)
            if (!file.isFile) return@getOrPut mutableListOf()
            Json
                .parseToJsonElement(file.readText())
                .jsonArray
                .map { element ->
                    val o = element.jsonObject
                    Entry(
                        method = o["method"]?.jsonPrimitive?.content ?: "GET",
                        url = o.getValue("url").jsonPrimitive.content,
                        query = o["query"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                        status = o.getValue("status").jsonPrimitive.int,
                        file = o.getValue("file").jsonPrimitive.content,
                        bodySha256 = o["bodySha256"]?.jsonPrimitive?.content,
                    )
                }.toMutableList()
        }

    companion object {
        const val INDEX = "index.json"
        private const val HASH_PREFIX = 12
        private const val MAX_NAME = 100

        /** The hosts recorded, and their directories. */
        val SOURCES: Map<String, String> =
            mapOf(
                "musicbrainz.org" to "musicbrainz",
                "www.wikidata.org" to "wikidata",
                "en.wikipedia.org" to "wikipedia",
                "api.anthropic.com" to "anthropic",
            )

        /** The query parameters every request of a source has, left out of file names (as `record.py`). */
        private val CONSTANT =
            setOf(
                "fmt",
                "format",
                "formatversion",
                "limit",
                "inc",
                "props",
                "languages",
                "sitefilter",
                "redirects",
                "prop",
                "ppprop",
                "exintro",
                "explaintext",
                "srnamespace",
                "srlimit",
                "origin",
                "utf8",
            )

        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY: Json =
            Json {
                prettyPrint = true
                prettyPrintIndent = " "
            }

        fun sourceOf(url: String): String? = SOURCES[hostOf(url)]

        /** The host of an absolute `https` URL, as the recorded URLs are written. */
        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?')

        private fun pathOf(url: String): String =
            url.substringAfter("://").substringBefore('?').let { if ('/' in it) it.substring(it.indexOf('/')) else "" }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        // `record.py`'s `name_for`: the path and the query values that vary, as a slug.
        fun nameOf(request: NetRequest): String {
            val path = pathOf(request.url).split('/').filter { it.isNotEmpty() && it !in PATH_CONSTANT }
            val values =
                request.query
                    .toSortedMap()
                    .filterKeys { it !in CONSTANT }
                    .values
            val slug =
                (path + values)
                    .joinToString(" ")
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
            return slug.ifEmpty { "root" }.take(MAX_NAME).trimEnd('-')
        }

        private val PATH_CONSTANT = setOf("ws", "2", "w", "api.php")

        /** [json] pretty-printed as the Python recorder writes it, or as it is if it is not JSON. */
        fun pretty(json: String): String =
            runCatching { PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json)) + "\n" }
                .getOrDefault(json)
    }
}

/**
 * A [NetClient] that answers from [store], without the network. A request with no recording is a
 * miss, listed in [misses], and fails as offline: the run goes on, and the CLI reports and fails on
 * the misses of the sources, so a replay never silently passes on less than it recorded.
 */
class ReplayNetClient(
    private val store: FixtureStore,
) : NetClient {
    private val missed = mutableListOf<String>()

    val misses: List<String> get() = synchronized(missed) { missed.toList() }

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        val (entry, file) =
            store.find(request) ?: run {
                synchronized(missed) { missed += describe(request) }
                return NetResult.Failure(NetError.Offline(NOT_RECORDED))
            }
        val type = if (file.extension == "sse") "text/event-stream" else "application/json"
        return NetResult.Success(NetResponse(entry.status, mapOf("content-type" to listOf(type)), file.readBytes()))
    }

    override fun close() = Unit

    companion object {
        const val NOT_RECORDED = "NotRecorded"
        private const val SHOWN_HASH = 12

        fun describe(request: NetRequest): String {
            val query =
                request.query
                    .toSortedMap()
                    .entries
                    .joinToString("&") { "${it.key}=${it.value}" }
            val body = request.body?.let { " body ${FixtureStore.sha256(it.text).take(SHOWN_HASH)}" }.orEmpty()
            return "${request.method} ${request.url}${if (query.isEmpty()) "" else "?$query"}$body"
        }
    }
}

/**
 * A [NetClient] that sends the requests of the [recorded] sources (directory names) through [live]
 * and records each answer in [store]; every other request is replayed from [store], so recording one
 * source leaves the others' recordings as they are. MusicBrainz responses lose their `tags` and
 * `genres` (CC BY-NC-SA 3.0 supplementary data a search returns unasked), as `record.py` does; an
 * Anthropic stream's message id becomes `msg_recorded`. No key reaches a file: only the URL, query,
 * body hash and body of a request are kept, and a keyless source's request that carries credentials
 * is refused.
 */
class RecordingNetClient(
    private val live: NetClient,
    private val store: FixtureStore,
    private val recorded: Set<String>,
) : NetClient {
    private val replay = ReplayNetClient(store)
    private val refused = mutableListOf<String>()

    /** The requests replayed with no recording, and the answers not recorded because they could not be read. */
    val misses: List<String> get() = replay.misses + synchronized(refused) { refused.toList() }

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        val source = FixtureStore.sourceOf(request.url)
        if (source == null || source !in recorded) return replay.send(request)
        checkKeyless(source, request)
        val result = live.send(request)
        if (result is NetResult.Success) {
            // A MusicBrainz body that cannot be read cannot have its tags removed, so it is not kept.
            val raw = result.value.body
            val body = if (source == MUSICBRAINZ) withoutTags(raw) else FixtureStore.pretty(raw)
            if (body == null) {
                synchronized(refused) { refused += "${ReplayNetClient.describe(request)} (not recorded: not JSON)" }
            } else {
                synchronized(store) { store.record(request, result.value.status, body, "json") }
            }
        }
        return result
    }

    override suspend fun stream(
        request: NetRequest,
        onLine: suspend (String) -> Unit,
    ): NetResult<NetResponse> {
        val source = FixtureStore.sourceOf(request.url)
        if (source == null || source !in recorded) return replay.stream(request, onLine)
        val lines = mutableListOf<String>()
        val result =
            live.stream(request) { line ->
                lines += line
                onLine(line)
            }
        if (result is NetResult.Success && result.value.isSuccess) {
            val body = lines.joinToString("\n", postfix = "\n").replace(MESSAGE_ID, "\"id\":\"msg_recorded\"")
            synchronized(store) { store.record(request, result.value.status, body, "sse") }
        }
        return result
    }

    override fun close() = live.close()

    private fun checkKeyless(
        source: String,
        request: NetRequest,
    ) {
        if (source == ANTHROPIC) return
        check(request.secrets.query.isEmpty() && request.secrets.headers.isEmpty() && !request.sensitiveUrl) {
            "Only keyless requests of $source are recorded"
        }
    }

    private companion object {
        const val MUSICBRAINZ = "musicbrainz"
        const val ANTHROPIC = "anthropic"
        val MESSAGE_ID = Regex("\"id\":\\s*\"msg_[A-Za-z0-9_]+\"")

        // Null when the body is not a JSON object or array (the parser takes a bare word as a value).
        fun withoutTags(body: String): String? =
            runCatching { Json.parseToJsonElement(body) }
                .getOrNull()
                ?.takeIf { it is JsonObject || it is JsonArray }
                ?.let { FixtureStore.PRETTY.encodeToString(JsonElement.serializer(), strip(it)) + "\n" }

        fun strip(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject ->
                    JsonObject(
                        element.filterKeys { it != "tags" && it != "genres" }.mapValues { strip(it.value) },
                    )
                is JsonArray -> JsonArray(element.map(::strip))
                else -> element
            }
    }
}
