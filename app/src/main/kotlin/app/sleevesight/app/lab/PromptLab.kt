package app.sleevesight.app.lab

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.OtherCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.LabJson
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.LabSettings
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The prompt lab (#1003), made only in the debug build (`AppGraph`, `BuildConfig.DEBUG`): prompt
 * variants for each kind of AI text ([PromptKind], [LabPrompts]), written with in place of the
 * release prompt while developer mode is on ([on]), or compared with it from the same evidence, and
 * each comparison logged on the phone for a tally.
 *
 * - **Its settings** ([settings]) and **its log** ([log]) are kept in [dir], read once at start, each
 *   change written at once, on [io].
 * - **Writing with a variant** ([through]): each AI service's client is wrapped, and a request the lab
 *   varies (told by its opening line, [LabPrompts.kindOf]) is sent with its kind's variant in use, if
 *   not the release prompt's, taken once for the whole call. Each reply is noted by its text
 *   ([written]) with the variant that wrote it and the request as built, in memory only, with the
 *   client and key it went through, for a comparison from exactly that request.
 * - **Kept by variant** ([cache]): a card is kept under the key of the variant that wrote it, told by
 *   its text, so a switch while a reply streams does not move it; a switch writes anew, and switching
 *   back finds the card the release prompt wrote. A card read is noted as the variant in use's.
 *
 * Developer mode off, nothing is varied, and the lab's place in Settings is hidden with the rest.
 */
@Suppress("TooManyFunctions") // Its settings, its log, and the client and cache it wraps.
class PromptLab(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val onState = MutableStateFlow(false)
    private val settingsState = MutableStateFlow(LabSettings())
    private val logState = MutableStateFlow<List<LabComparison>>(emptyList())
    private val writtenState = MutableStateFlow<Map<PromptKind, List<Written>>>(emptyMap())
    private val files = Mutex()

    /** Whether the lab is on: developer mode, as last read. */
    val on: StateFlow<Boolean> = onState.asStateFlow()

    /** The lab's settings. */
    val settings: StateFlow<LabSettings> = settingsState.asStateFlow()

    /** The comparisons logged, the oldest first. */
    val log: StateFlow<List<LabComparison>> = logState.asStateFlow()

    init {
        scope.launch(io) {
            files.withLock {
                read(SETTINGS)?.let { settingsState.value = LabJson.readSettings(it) }
                read(LOG)?.let { logState.value = LabJson.readLog(it) }
            }
        }
    }

    /** Developer mode, as read: the lab varies nothing while it is off. */
    fun developerMode(on: Boolean) {
        onState.value = on
    }

    /** Changes the settings, and keeps them. */
    fun update(change: (LabSettings) -> LabSettings) {
        settingsState.update(change)
        save(SETTINGS)
    }

    /** The variant [kind] is written with now: its id, the release prompt's while the lab is off. */
    fun inUse(kind: PromptKind): String = if (onState.value) settingsState.value.inUse(kind) else LabPrompts.RELEASE

    /** Logs [comparison], and keeps the log. */
    fun record(comparison: LabComparison) {
        logState.update { it + comparison }
        save(LOG)
    }

    /** Clears the log. */
    fun clear() {
        logState.value = emptyList()
        save(LOG)
    }

    /** The log as the export and the tester report give it: JSON, laid out for reading. */
    fun logText(): String = LabJson.log(logState.value, readable = true)

    /** Writes the log ([logText]) to the stream [open] gives; whether it was written. */
    suspend fun export(open: () -> OutputStream?): Boolean =
        withContext(io) {
            runCatching { open()?.use { it.write(logText().toByteArray()) } != null }.getOrDefault(false)
        }

    /**
     * What wrote [shown], a text of [kind] on screen: the variant, and, when it was written since the
     * app started, the request it was written from, as built ([Written.sent]); null for a text the lab
     * has not seen written or read. Told by the text itself ([LabPrompts.sameText]), so a comparison
     * starts from exactly the request that wrote what is shown, and a credit names the prompt that
     * wrote it, whatever the lab is set to now.
     */
    fun written(
        kind: PromptKind,
        shown: String,
    ): Written? {
        val key = LabPrompts.textKey(kind, shown)
        if (key.isEmpty()) return null
        return writtenState.value[kind]?.lastOrNull { LabPrompts.sameText(it.text, key) }
    }

    /** What the lab has seen written or read, by kind, the latest last: for a screen to follow it. */
    val seen: StateFlow<Map<PromptKind, List<Written>>> get() = writtenState

    /** [provider], each request the lab varies sent with its kind's variant in use, and remembered ([written]). */
    fun through(provider: AiProvider): AiProvider = Varied(provider)

    /** [cache], each card the lab varies kept under the key of the variant that wrote it. */
    fun cache(cache: PipelineCache): PipelineCache = Kept(cache)

    /**
     * A request the lab varies as it was built ([request]), with the client ([provider]) and key it
     * went through, held in memory only, for a comparison: never written, logged or exported.
     */
    class Sent(
        val request: CompletionRequest,
        val provider: AiProvider,
        val key: Secret,
        val at: Instant,
    )

    /**
     * A text of a kind as the lab saw it written or read ([text], its [LabPrompts.textKey]): by the
     * [variant] with [instructions] (null for the release prompt's), from [sent] when the lab saw it
     * written.
     */
    class Written(
        val text: String,
        val variant: String,
        val instructions: String?,
        val sent: Sent?,
    )

    // The variant [kind] is written with now, and its instructions: one snapshot, taken once per call.
    private fun snapshot(kind: PromptKind): Pair<String, String?> {
        val settings = settingsState.value
        val id = if (onState.value) settings.inUse(kind) else LabPrompts.RELEASE
        return id to settings.instructions(kind, id)
    }

    private inner class Varied(
        private val provider: AiProvider,
    ) : AiProvider by provider {
        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> {
            val kind = LabPrompts.kindOf(request) ?: return provider.stream(request, key)
            // One snapshot for the whole call: a switch while it streams changes neither what is
            // sent nor the key its reply is kept under (`Kept.keep`, by the reply).
            val (id, instructions) = snapshot(kind)
            val sent = Sent(request.copy(exchanges = emptyList()), provider, key, Clock.System.now())
            val reply = StringBuilder()
            return provider
                .stream(instructions?.let { LabPrompts.withInstructions(request, it) } ?: request, key)
                .onEach { event ->
                    when (event) {
                        is CompletionEvent.Delta -> reply.append(event.text)
                        is CompletionEvent.Done ->
                            note(kind, Written(LabPrompts.textKey(kind, reply.toString()), id, instructions, sent))
                        else -> Unit
                    }
                }
        }
    }

    // Keeps [written] as the latest of its kind, a sent request it replaces kept with it.
    private fun note(
        kind: PromptKind,
        written: Written,
    ) {
        if (written.text.isEmpty()) return
        writtenState.update { all ->
            val before = all[kind].orEmpty()
            val same = before.lastOrNull { it.text == written.text && it.variant == written.variant }
            val kept =
                if (written.sent == null &&
                    same?.sent != null
                ) {
                    Written(written.text, written.variant, written.instructions, same.sent)
                } else {
                    written
                }
            all + (kind to (before.filterNot { it.text == written.text } + kept).takeLast(REMEMBERED))
        }
    }

    // [key] as a card written by variant [id] with [instructions] is kept: its style's hash joined
    // with the variant's id and its instructions' hash; the release prompt's key as it is.
    private fun keyed(
        key: CardKey,
        id: String,
        instructions: String?,
    ): CardKey =
        if (instructions == null) {
            key
        } else {
            key.copy(styleHash = key.styleHash + "+lab:" + id + ":" + instructions.hashCode().toUInt().toString(HEX))
        }

    private inner class Kept(
        private val cache: PipelineCache,
    ) : PipelineCache by cache {
        // A card read: under the key of the variant in use now, noted as that variant's.
        private suspend fun <T> reading(
            key: CardKey,
            read: suspend (CardKey) -> T,
            text: (T) -> String?,
        ): T {
            val kind = LabPrompts.kindOfCard(key.kind, key.subject) ?: return read(key)
            val (id, instructions) = snapshot(kind)
            val found = read(keyed(key, id, instructions))
            text(found)?.let { note(kind, Written(LabPrompts.textKey(kind, it), id, instructions, null)) }
            return found
        }

        // A card kept: under the key of the variant that wrote it, told by its text; one the lab did
        // not see written, under the variant in use now.
        private fun keyFor(
            key: CardKey,
            text: String,
        ): CardKey {
            val kind = LabPrompts.kindOfCard(key.kind, key.subject) ?: return key
            val by = written(kind, text)
            val (id, instructions) = by?.let { it.variant to it.instructions } ?: snapshot(kind)
            if (by == null) note(kind, Written(LabPrompts.textKey(kind, text), id, instructions, null))
            return keyed(key, id, instructions)
        }

        override suspend fun card(key: CardKey): String? = reading(key, cache::card) { it }

        override suspend fun keptCard(key: CardKey): KeptCard? = reading(key, cache::keptCard) { it?.text }

        override suspend fun keptByOthers(key: CardKey): List<OtherCard> {
            val kind = LabPrompts.kindOfCard(key.kind, key.subject) ?: return cache.keptByOthers(key)
            val (id, instructions) = snapshot(kind)
            return cache.keptByOthers(keyed(key, id, instructions)).map { other ->
                note(kind, Written(LabPrompts.textKey(kind, other.card.text), id, instructions, null))
                other.copy(key = other.key.copy(styleHash = key.styleHash))
            }
        }

        override suspend fun keep(
            key: CardKey,
            text: String,
        ) = cache.keep(keyFor(key, text), text)

        override suspend fun keep(
            key: CardKey,
            text: String,
            writtenAt: Instant,
        ) = cache.keep(keyFor(key, text), text, writtenAt)

        override suspend fun keep(
            key: CardKey,
            text: String,
            writtenAt: Instant,
            rewritten: Set<RewritePart>,
        ) = cache.keep(keyFor(key, text), text, writtenAt, rewritten)
    }

    private fun read(name: String): String? =
        runCatching { File(dir, name).takeIf { it.isFile }?.readText() }.getOrNull()

    // Writes [name] as it is when the write comes, so writes that come out of order still end with
    // the latest.
    private fun save(name: String) {
        scope.launch(io) {
            files.withLock {
                val text = if (name == SETTINGS) LabJson.settings(settingsState.value) else LabJson.log(logState.value)
                runCatching {
                    dir.mkdirs()
                    val temp = File(dir, "$name.tmp")
                    temp.writeText(text)
                    temp.renameTo(File(dir, name))
                }
            }
        }
    }

    private companion object {
        const val SETTINGS = "settings.json"
        const val LOG = "log.json"

        // The texts remembered of each kind: a listening session back.
        const val REMEMBERED = 64
        const val HEX = 16
    }
}
