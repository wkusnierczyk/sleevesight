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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
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
 *   not the release prompt's. Every first request of such a kind is remembered, as built, in memory
 *   only, with the client and key it went through ([sent]), for a comparison from the same evidence.
 * - **Kept by variant** ([cache]): a card written with a variant is kept under a key of its own, so a
 *   switch writes anew, and switching back finds the card the release prompt wrote.
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
    private val sentByKind = ConcurrentHashMap<PromptKind, List<Sent>>()
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
     * The latest first request of [kind] whose text holds each of [needles] (the track's title, a
     * genre's name, each as its prompt writes it), as built, before any variant; null when none was
     * sent since the app started.
     */
    fun sent(
        kind: PromptKind,
        needles: Collection<String>,
    ): Sent? =
        sentByKind[kind]?.lastOrNull { sent ->
            val text = (sent.request.system ?: "") + "\n" + sent.request.prompt
            needles.all { it in text }
        }

    /** [provider], each request the lab varies sent with its kind's variant in use, and remembered ([sent]). */
    fun through(provider: AiProvider): AiProvider = Varied(provider)

    /** [cache], each card the lab varies kept under its variant's own key while one is in use. */
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

    private inner class Varied(
        private val provider: AiProvider,
    ) : AiProvider by provider {
        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> {
            val kind = LabPrompts.kindOf(request) ?: return provider.stream(request, key)
            if (request.exchanges.isEmpty()) remember(kind, Sent(request, provider, key, Clock.System.now()))
            val instructions = settingsState.value.instructions(kind, inUse(kind))
            return provider.stream(instructions?.let { LabPrompts.withInstructions(request, it) } ?: request, key)
        }
    }

    private fun remember(
        kind: PromptKind,
        sent: Sent,
    ) {
        sentByKind.compute(kind) { _, before -> (before.orEmpty() + sent).takeLast(REMEMBERED) }
    }

    // [key] as kept while its kind is written with a variant: its style's hash joined with the
    // variant's id and its instructions' hash, so the release prompt's card is never read for it.
    private fun keyed(key: CardKey): CardKey {
        val kind = LabPrompts.kindOfCard(key.kind, key.subject)
        val id = kind?.let(::inUse)
        val instructions = if (kind != null && id != null) settingsState.value.instructions(kind, id) else null
        return if (instructions == null) {
            key
        } else {
            key.copy(styleHash = key.styleHash + "+lab:" + id + ":" + instructions.hashCode().toUInt().toString(HEX))
        }
    }

    private inner class Kept(
        private val cache: PipelineCache,
    ) : PipelineCache by cache {
        override suspend fun card(key: CardKey): String? = cache.card(keyed(key))

        override suspend fun keptCard(key: CardKey): KeptCard? = cache.keptCard(keyed(key))

        override suspend fun keptByOthers(key: CardKey): List<OtherCard> =
            cache.keptByOthers(keyed(key)).map { it.copy(key = it.key.copy(styleHash = key.styleHash)) }

        override suspend fun keep(
            key: CardKey,
            text: String,
        ) = cache.keep(keyed(key), text)

        override suspend fun keep(
            key: CardKey,
            text: String,
            writtenAt: Instant,
        ) = cache.keep(keyed(key), text, writtenAt)

        override suspend fun keep(
            key: CardKey,
            text: String,
            writtenAt: Instant,
            rewritten: Set<RewritePart>,
        ) = cache.keep(keyed(key), text, writtenAt, rewritten)
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

        // The first requests remembered of each kind: a few tracks back.
        const val REMEMBERED = 12
        const val HEX = 16
    }
}
