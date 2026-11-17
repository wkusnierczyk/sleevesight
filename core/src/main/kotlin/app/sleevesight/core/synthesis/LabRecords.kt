package app.sleevesight.core.synthesis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

// The prompt lab's settings and its log (#1003), and their JSON: the file each is kept in on the
// phone, and the log's export.

/**
 * The prompt lab's settings, per kind ([PromptKind]): the variant each is written with ([inUse], the
 * release prompt unless set), the variants a comparison writes ([compared], from
 * [LabPrompts.FEWEST_COMPARED] to [LabPrompts.MOST_COMPARED]), and the *Custom* instructions
 * ([custom], [LabPrompts.ROLE] until edited). An id the kind does not have reads as unset.
 *
 * The lab's switch ([enabled], #1388), off until turned on: off, every kind is written with the
 * release prompt and no comparison is offered, the choices per kind kept for when it is turned on.
 */
data class LabSettings(
    val inUse: Map<PromptKind, String> = emptyMap(),
    val compared: Map<PromptKind, List<String>> = emptyMap(),
    val custom: Map<PromptKind, String> = emptyMap(),
    val enabled: Boolean = false,
) {
    /** The variant [kind] is written with: its id, [LabPrompts.RELEASE] unless set. */
    fun inUse(kind: PromptKind): String = inUse[kind]?.takeIf { it in ids(kind) } ?: LabPrompts.RELEASE

    /** The variants a comparison of [kind] writes, in the lab's order. */
    fun compared(kind: PromptKind): List<String> {
        val set = compared[kind]?.filter { it in ids(kind) }?.toSet() ?: defaultCompared(kind).toSet()
        return ids(kind).filter { it in set }
    }

    /** [kind]'s *Custom* instructions. */
    fun custom(kind: PromptKind): String = custom[kind] ?: LabPrompts.ROLE

    /** The instructions of [kind]'s variant [id]; null for the release prompt's, which are left as built. */
    fun instructions(
        kind: PromptKind,
        id: String,
    ): String? =
        when (id) {
            LabPrompts.RELEASE -> null
            LabPrompts.CUSTOM -> custom(kind)
            else -> LabPrompts.variant(kind, id)?.instructions
        }

    /** These settings with [kind] written with [id]. */
    fun use(
        kind: PromptKind,
        id: String,
    ): LabSettings = copy(inUse = inUse + (kind to id))

    /**
     * These settings with [id] taking part in [kind]'s comparisons, or no longer ([on]); none added
     * past [LabPrompts.MOST_COMPARED].
     */
    fun compare(
        kind: PromptKind,
        id: String,
        on: Boolean,
    ): LabSettings {
        val now = compared(kind)
        val next =
            when {
                on && id !in now && now.size < LabPrompts.MOST_COMPARED -> now + id
                !on -> now - id
                else -> return this
            }
        return copy(compared = compared + (kind to next))
    }

    /** These settings with the lab switched on or off ([on]), the choices per kind kept either way. */
    fun withEnabled(on: Boolean): LabSettings = copy(enabled = on)

    /** These settings with [kind]'s *Custom* instructions [text]. */
    fun withCustom(
        kind: PromptKind,
        text: String,
    ): LabSettings = copy(custom = custom + (kind to text))

    companion object {
        /** Every variant id [kind] has, in the lab's order: the release prompt, the drafts, *Custom*. */
        fun ids(kind: PromptKind): List<String> =
            listOf(LabPrompts.RELEASE) + LabPrompts.variants(kind).map { it.id } + LabPrompts.CUSTOM

        /** What a comparison of [kind] writes until set: the release prompt and the first two drafts. */
        fun defaultCompared(kind: PromptKind): List<String> =
            listOf(LabPrompts.RELEASE) + LabPrompts.variants(kind).take(2).map { it.id }
    }
}

/** A tester's mark on a version: good or bad (neither is no mark). */
enum class LabMark(
    val key: String,
) {
    GOOD("good"),
    BAD("bad"),
}

/**
 * One version of a comparison, as logged: the number it was [shown] under, the variant ([prompt], its
 * id), the [text] written, or why none was ([failed]), the tester's [mark] and [note].
 */
data class LabVersion(
    val shown: Int,
    val prompt: String,
    val text: String?,
    val failed: String? = null,
    val mark: LabMark? = null,
    val note: String = "",
)

/**
 * One comparison, logged when its prompts were shown: when ([time]), the app's version ([app]), the
 * [kind], the track ([title], [artist], [album]), the AI [service], [model] and [effort], the
 * [evidence] (the request's data, each line by its id, as sent), each variant's opening
 * [instructions] as sent, by id, and the [versions].
 */
data class LabComparison(
    val time: Instant,
    val app: String,
    val kind: PromptKind,
    val title: String?,
    val artist: String?,
    val album: String?,
    val service: String,
    val model: String,
    val effort: String?,
    val evidence: String,
    val instructions: Map<String, String>,
    val versions: List<LabVersion>,
)

/** The lab's settings and log as JSON, as kept on the phone and exported. */
@Suppress("TooManyFunctions") // A writer and a reader for each record, and their parts.
object LabJson {
    private val json = Json { prettyPrint = false }
    private val pretty = Json { prettyPrint = true }

    /** [settings] as JSON. */
    fun settings(settings: LabSettings): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("in_use", byKind(settings.inUse) { JsonPrimitive(it) })
                put("compared", byKind(settings.compared) { ids -> JsonArray(ids.map(::JsonPrimitive)) })
                put("custom", byKind(settings.custom) { JsonPrimitive(it) })
                put("enabled", settings.enabled)
            },
        )

    /** The settings in [text], as [settings] wrote them; the defaults for any it cannot read. */
    fun readSettings(text: String): LabSettings {
        val root = parse(text) as? JsonObject ?: return LabSettings()
        return LabSettings(
            inUse = readByKind(root["in_use"]) { (it as? JsonPrimitive)?.contentOrNull },
            compared =
                readByKind(root["compared"]) { element ->
                    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                },
            custom = readByKind(root["custom"]) { (it as? JsonPrimitive)?.contentOrNull },
            // Off unless kept on: settings kept before the switch (#1388) read as off.
            enabled = (root["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** [log] as JSON, an array of comparisons, the oldest first; laid out for reading when [readable]. */
    fun log(
        log: List<LabComparison>,
        readable: Boolean = false,
    ): String =
        (if (readable) pretty else json).encodeToString(JsonElement.serializer(), JsonArray(log.map(::comparison)))

    /** The comparisons in [text], as [log] wrote them, leaving out any it cannot read. */
    fun readLog(text: String): List<LabComparison> =
        (parse(text) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::readComparison) }

    private fun comparison(c: LabComparison): JsonObject =
        buildJsonObject {
            put("time", c.time.toString())
            put("app", c.app)
            put("kind", c.kind.key)
            put(
                "track",
                buildJsonObject {
                    put("title", c.title)
                    put("artist", c.artist)
                    put("album", c.album)
                },
            )
            put("service", c.service)
            put("model", c.model)
            put("effort", c.effort ?: DEFAULT_EFFORT)
            put("evidence", c.evidence)
            put("instructions", JsonObject(c.instructions.mapValues { JsonPrimitive(it.value) }))
            put("versions", buildJsonArray { c.versions.forEach { add(version(it)) } })
        }

    private fun version(v: LabVersion): JsonObject =
        buildJsonObject {
            put("shown", v.shown)
            put("prompt", v.prompt)
            put("text", v.text)
            v.failed?.let { put("failed", it) }
            put("mark", v.mark?.key)
            put("note", v.note)
        }

    private fun readComparison(o: JsonObject): LabComparison? {
        val kind = o.string("kind")?.let(PromptKind::of)
        val time = o.string("time")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (kind == null || time == null) return null
        val track = o["track"] as? JsonObject
        return LabComparison(
            time = time,
            app = o.string("app").orEmpty(),
            kind = kind,
            title = track?.string("title"),
            artist = track?.string("artist"),
            album = track?.string("album"),
            service = o.string("service").orEmpty(),
            model = o.string("model").orEmpty(),
            effort = o.string("effort")?.takeUnless { it == DEFAULT_EFFORT },
            evidence = o.string("evidence").orEmpty(),
            instructions =
                (o["instructions"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
                    ?.toMap()
                    .orEmpty(),
            versions = (o["versions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::readVersion) },
        )
    }

    private fun readVersion(o: JsonObject): LabVersion? {
        val shown = (o["shown"] as? JsonPrimitive)?.intOrNull
        val prompt = o.string("prompt")
        if (shown == null || prompt == null) return null
        return LabVersion(
            shown = shown,
            prompt = prompt,
            text = o.string("text"),
            failed = o.string("failed"),
            mark = o.string("mark")?.let { key -> LabMark.entries.firstOrNull { it.key == key } },
            note = o.string("note").orEmpty(),
        )
    }

    private fun <T> byKind(
        map: Map<PromptKind, T>,
        element: (T) -> JsonElement,
    ): JsonObject = JsonObject(map.entries.associate { (kind, value) -> kind.key to element(value) })

    private fun <T : Any> readByKind(
        element: JsonElement?,
        value: (JsonElement) -> T?,
    ): Map<PromptKind, T> =
        (element as? JsonObject)
            ?.mapNotNull { (key, v) -> PromptKind.of(key)?.let { kind -> value(v)?.let { kind to it } } }
            ?.toMap()
            .orEmpty()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)
            ?.takeUnless {
                it is JsonNull
            }?.contentOrNull

    private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    // The effort as the log names the service's default.
    private const val DEFAULT_EFFORT = "default"
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
