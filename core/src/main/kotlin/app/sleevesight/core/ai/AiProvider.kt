package app.sleevesight.core.ai

import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An AI service the user chose, with their key (`design/architecture.md`, *Synthesis and
 * grounding*). The model only narrates: one prompt in, streamed text out. The one exception is
 * *AI web search* (#801), whose *Up to 3 times* gives the model a web search tool
 * ([CompletionRequest.tools]), where the client supports tool use ([usesTools]). The
 * implementations are small clients in `:ai`, over `:net` (D3).
 */
interface AiProvider {
    val id: ProviderId

    /**
     * Whether this client sends [CompletionRequest.tools] and reads the model's calls to them
     * ([CompletionEvent.ToolUse]); a client that does not ignores the tools, and its model answers
     * without them.
     */
    val usesTools: Boolean get() = false

    /** The models [key] may use, as the service lists them, or why they could not be listed. */
    suspend fun models(key: Secret): ModelList

    /**
     * Writes a completion of [request], as a cold flow: each collection sends one request, and
     * cancelling the collector cancels it. It emits [CompletionEvent.Delta]s as the text arrives,
     * then exactly one [CompletionEvent.Done] or [CompletionEvent.Error], and ends. It does not
     * throw for a failure of the service or the network.
     */
    fun stream(
        request: CompletionRequest,
        key: Secret,
    ): Flow<CompletionEvent>
}

/**
 * One completion: [prompt] as the single user message to [model]. [maxTokens] bounds the reply,
 * including any reasoning the model does before it, which counts against it. [effort] is how much
 * the model may reason before it answers, where the provider has such a control; null leaves the
 * provider's default.
 *
 * With [tools] (#801, a client that [AiProvider.usesTools]), the model may call them: its turn then
 * ends with [CompletionEvent.ToolUse] and [StopReason.TOOL_USE], and the next request sends the
 * same prompt and tools with that turn and the tools' answers after it ([exchanges]), as many as
 * there have been. [toolsAllowed] false keeps the tools (a conversation that has used them must
 * still declare them) but has the model answer without calling one: the last turn of a loop.
 */
data class CompletionRequest(
    val model: String,
    val prompt: String,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val effort: ReasoningEffort? = null,
    val tools: List<ToolSpec> = emptyList(),
    val exchanges: List<ToolExchange> = emptyList(),
    val toolsAllowed: Boolean = true,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
    }

    companion object {
        /**
         * Room for a card of a few sentences or paragraphs and for the reasoning that current
         * models do at low effort before it; a reply that reaches it ends with [StopReason.MAX_TOKENS].
         */
        const val DEFAULT_MAX_TOKENS: Int = 4096

        /** Room for the reasoning of [ReasoningEffort.MEDIUM] as well ([maxTokensFor]). */
        const val MEDIUM_MAX_TOKENS: Int = 8192

        /** Room for the reasoning of [ReasoningEffort.HIGH], or a service's default, as well ([maxTokensFor]). */
        const val HIGH_MAX_TOKENS: Int = 16384

        /**
         * The limit for a card written at [effort] (#665). The reasoning counts against the limit,
         * and more effort reasons longer: 4096 left a high-effort call room to end with no text.
         * The most a recorded insight used was 1226 tokens out, at low effort, so low keeps 4096. A
         * service's default ([effort] null) can be its highest (Claude Opus reasons at high), so it
         * gets the most. A higher limit costs nothing unless the model uses it. The providers keep
         * [DEFAULT_MAX_TOKENS] for a model that takes no effort control: they know no model's
         * output maximum, and an older model's can be as low as that.
         */
        fun maxTokensFor(effort: ReasoningEffort?): Int =
            when (effort) {
                ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> DEFAULT_MAX_TOKENS
                ReasoningEffort.MEDIUM -> MEDIUM_MAX_TOKENS
                ReasoningEffort.HIGH, null -> HIGH_MAX_TOKENS
            }
    }
}

/**
 * A tool the model may call (#801): its [name], what it does for the model to read ([description]),
 * and the JSON Schema of its input ([parameters], a JSON object as text).
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String,
)

/** A call the model made: the service's [id] for it, the tool's [name], and its input ([arguments], JSON text). */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * A model's turn that ended in tool calls ([calls]), and [replay]: the turn as its service must be
 * sent it again in the next request (its content, reasoning included, as the service's own JSON),
 * which only the client that made it reads.
 */
data class ToolTurn(
    val calls: List<ToolCall>,
    val replay: String,
)

/**
 * A tool's answer to the call [callId], as text; [isError] when the tool failed or the call could not
 * be made (Anthropic's `is_error`; a service with no such mark reads the text alone).
 */
data class ToolResult(
    val callId: String,
    val content: String,
    val isError: Boolean = false,
)

/** One round of tool use: the model's [turn], and the [results] sent back for its calls. */
data class ToolExchange(
    val turn: ToolTurn,
    val results: List<ToolResult>,
)

/**
 * How much a model may reason before it answers: a provider maps it to its own control (Anthropic's
 * `output_config.effort`, Google's `thinkingLevel`), or ignores it where it has none. Less is faster
 * and cheaper.
 *
 * These are the levels of every provider's scale, by name: each provider offers its own levels from
 * them, with its own default ([EffortScale], `Settings.EFFORT_SCALES`), and is sent only those. A
 * provider whose scale has a level not named here adds it, with its label in the settings. The name
 * is stored (the settings, the synthesis cache's key), so a level is never renamed.
 */
enum class ReasoningEffort {
    /** Little or no reasoning: Google's `minimal` (#229). Not on Anthropic's scale. */
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * One provider's reasoning-effort scale: the [levels] it offers, from least to most, and the
 * [default] used until the user chooses one, where null is the provider's own default (no effort
 * sent). Its levels are what the settings offer for the provider, with *the service's default*.
 *
 * The levels a service takes can differ between its models (some take none, some name their levels
 * otherwise, some take a budget of reasoning tokens instead). So the provider's client maps each
 * level to the control of the model it calls, and drops or clamps a level that model does not take,
 * as `AnthropicProvider.takesEffort` drops the effort for a model that does not take it.
 */
data class EffortScale(
    val levels: List<ReasoningEffort>,
    val default: ReasoningEffort?,
) {
    init {
        require(levels.isNotEmpty()) { "An effort scale needs a level" }
        require(levels.distinct() == levels) { "An effort scale lists each level once, was $levels" }
        require(default == null || default in levels) { "The default $default is not on the scale $levels" }
    }

    /** [effort] if this scale offers it, or null (the provider's default); else the [default]. */
    fun coerce(effort: ReasoningEffort?): ReasoningEffort? = if (effort == null || effort in levels) effort else default
}

/** What a completion stream emits. */
sealed interface CompletionEvent {
    /** The next piece of the reply's text. */
    data class Delta(
        val text: String,
    ) : CompletionEvent

    /**
     * The model's turn ended in calls to the request's tools (#801): emitted once, just before the
     * [Done] with [StopReason.TOOL_USE].
     */
    data class ToolUse(
        val turn: ToolTurn,
    ) : CompletionEvent

    /** The reply is complete. */
    data class Done(
        val usage: Usage,
        val stopReason: StopReason,
    ) : CompletionEvent

    /**
     * The reply failed; the text so far, if any, is incomplete. [retryAfter] is the service's
     * requested wait, when it gave one. [breakoff] says how a reply that had begun broke off, for
     * [AiErrorKind.INCOMPLETE] only, when the client can tell.
     */
    data class Error(
        val kind: AiErrorKind,
        val retryAfter: Duration? = null,
        val breakoff: Breakoff? = null,
    ) : CompletionEvent {
        init {
            require(breakoff == null || kind == AiErrorKind.INCOMPLETE) {
                "A breakoff is for an incomplete reply, not $kind"
            }
        }
    }
}

/**
 * How a reply broke off before its end ([AiErrorKind.INCOMPLETE]), for the card to say and the
 * diagnostics to record, never with the text (#665): its [cause]; the tokens the service had counted
 * by then ([usage]: Anthropic counts the input when the reply begins and the output only near its
 * end, OpenAI both only at the end, so they are often zero); and the transport's label for the
 * failure ([detail], such as `SocketTimeoutException`), null when the stream simply ended.
 */
data class Breakoff(
    val cause: BreakoffCause,
    val usage: Usage,
    val detail: String? = null,
)

/** Why a reply that had begun did not reach its end. */
enum class BreakoffCause {
    /** The stream ended, as the transport saw it, without the service's end of the reply. */
    NO_END,

    /** The connection was lost or cut mid-reply. */
    CONNECTION_LOST,

    /** The client stopped waiting: the reply fell silent, or took longer in all, than it allows. */
    TIMED_OUT,

    /** The reply was longer than the client reads. */
    TOO_LARGE,
}

/** Tokens billed for one completion, as the service reports them. */
data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
)

/** Why the model stopped. */
enum class StopReason {
    /** It finished. */
    END,

    /** It reached [CompletionRequest.maxTokens]: the reply is cut off. */
    MAX_TOKENS,

    /** It declined to answer. */
    REFUSAL,

    /** It called a tool ([CompletionEvent.ToolUse]): the reply goes on once the tools have answered. */
    TOOL_USE,

    /** A reason this client does not know. */
    OTHER,
}

/** Every way a provider call fails, for the card to explain and the log to record. */
enum class AiErrorKind {
    /** No connection, or none that answered. */
    OFFLINE,

    /** The key is missing, wrong, revoked, or not allowed this. */
    UNAUTHORIZED,

    /** The account cannot pay for the call. */
    BILLING,

    /**
     * The account's quota for a period is used up (Google's requests per day): it resets later,
     * or a higher tier lifts it. Not a matter of credit, and no retry mends it soon.
     */
    QUOTA_EXHAUSTED,

    /**
     * The service is not offered where the user is (Google's "User location is not supported"):
     * neither the key nor billing mends it.
     */
    UNAVAILABLE_HERE,

    /** Too many requests; try again later. */
    RATE_LIMITED,

    /** The service is overloaded; try again later. */
    OVERLOADED,

    /** The service failed. */
    SERVER,

    /** The model does not exist, or the key cannot use it. */
    MODEL_NOT_FOUND,

    /**
     * The model exists, but the account cannot use it as this client calls it: OpenAI's
     * organization not verified to stream it, Google's tier with no quota at all for it (a limit
     * of 0). Like [MODEL_NOT_FOUND], another model or service mends it, and no retry does.
     */
    MODEL_REFUSED,

    /** The service refused the request as malformed. */
    INVALID_REQUEST,

    /** The prompt is too large for the service. */
    REQUEST_TOO_LARGE,

    /**
     * The stream broke off or ended before the reply was complete; [CompletionEvent.Error.breakoff]
     * says how, when the client can tell.
     */
    INCOMPLETE,

    /** The service answered with something this client cannot read. */
    UNEXPECTED_RESPONSE,
}

/**
 * A model a provider offers. [createdAt] is its release, when the service says. [offered] is false
 * for a model the service still answers for those who already use it but closes to a new choice
 * (Anthropic's `lifecycle: deprecated`): it counts as listed for a model chosen before, and is not
 * offered for a new one.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val createdAt: Instant?,
    val offered: Boolean = true,
)

/** The result of [AiProvider.models]. */
sealed interface ModelList {
    data class Listed(
        val models: List<ModelInfo>,
    ) : ModelList {
        /** Whether [model] is listed, offered for a new choice or not. */
        fun has(model: String): Boolean = models.any { it.id == model }
    }

    data class Failed(
        val kind: AiErrorKind,
    ) : ModelList
}
