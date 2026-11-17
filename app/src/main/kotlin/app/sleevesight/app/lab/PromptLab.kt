package app.sleevesight.app.lab

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.LabJson
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.LabSettings
import app.sleevesight.core.synthesis.PromptChoice
import app.sleevesight.core.synthesis.PromptChoices
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The prompt lab (#1003), made only in the debug build (`AppGraph`, `BuildConfig.DEBUG`): prompt
 * variants for each kind of AI text ([PromptKind], [LabPrompts]), written with in place of the
 * release prompt while developer mode is on ([on]), or compared with it from the same evidence, and
 * each comparison logged on the phone for a tally.
 *
 * - **Its settings** ([settings]) and **its log** ([log]) are kept in [dir], read once at start, each
 *   change written at once, on [io].
 * - **Choices taken once** ([choices]): the variant each kind is written with, and its instructions,
 *   as the lab is set when a pipeline is built (`AppPipelineParts`). The pipeline keys each card with
 *   them (`PromptChoices.keyed`) and its client sends each request with them ([through]), so a card is
 *   kept under the variant that wrote it, and the text shown from it names it (`WrittenBy.variant`),
 *   whatever the lab is switched to meanwhile. A switch rebuilds the pipeline ([rebuilds]): later
 *   cards are written, and read, with the new choices.
 * - **Requests remembered** ([sent]): each request of a card ([CompletionRequest.card]) as sent last,
 *   the release prompt's opening line in place, with the tools' answers so far, in memory only, with the
 *   client and key it went through, for a comparison from exactly that request.
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
    private val sentByCard = LinkedHashMap<String, Sent>()
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

    /**
     * The lab's choices as set now (#1003): the variant each kind is written with and its instructions,
     * for a pipeline built now; [PromptChoices.NONE] while the lab is off.
     */
    fun choices(): PromptChoices {
        if (!onState.value) return PromptChoices.NONE
        val settings = settingsState.value
        return PromptChoices(
            PromptKind.entries.associateWith { kind ->
                settings.inUse(kind).let { id -> PromptChoice(id, settings.instructions(kind, id)) }
            },
        )
    }

    /**
     * When a pipeline is to be built again for the lab's choices: each change of them after the
     * first, a burst of typing in *Custom* taken as one.
     */
    @OptIn(FlowPreview::class)
    val rebuilds: Flow<Unit>
        get() =
            combine(onState, settingsState) { _, _ -> signature(choices()) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(REBUILD_QUIET)
                .map { }

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
     * The request that wrote the card [card] (`WrittenBy.card`), as last sent, with the tools' answers
     * it had; null for a card read from the cache, or written before the app started.
     */
    fun sent(card: String?): Sent? = card?.let { synchronized(sentByCard) { sentByCard[it] } }

    /** [provider], each request the lab varies sent with [choices]' variant for its kind, and remembered ([sent]). */
    fun through(
        provider: AiProvider,
        choices: PromptChoices,
    ): AiProvider = if (choices.on) Varied(provider, choices) else provider

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
        private val choices: PromptChoices,
    ) : AiProvider by provider {
        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> {
            val kind = LabPrompts.kindOf(request) ?: return provider.stream(request, key)
            request.card?.let { card -> remember(card, Sent(request, provider, key, Clock.System.now())) }
            val instructions = choices[kind]?.instructions
            return provider.stream(instructions?.let { LabPrompts.withInstructions(request, it) } ?: request, key)
        }
    }

    // Keeps [sent] as [card]'s request, the latest turn of its call; the oldest cards' dropped.
    private fun remember(
        card: String,
        sent: Sent,
    ) {
        synchronized(sentByCard) {
            sentByCard.remove(card)
            sentByCard[card] = sent
            while (sentByCard.size > REMEMBERED) sentByCard.remove(sentByCard.keys.first())
        }
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

        // The cards whose requests are remembered: a listening session back.
        const val REMEMBERED = 64

        // How long the lab's choices stay unchanged before a rebuild follows them.
        val REBUILD_QUIET = 800.milliseconds

        // The choices as a rebuild compares them.
        fun signature(choices: PromptChoices): List<PromptChoice?> = PromptKind.entries.map { choices[it] }
    }
}
