package app.sleevesight.core.ai

import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * An AI service the user chose, with their key (`design/architecture.md`, *Synthesis and
 * grounding*). The model only narrates: one prompt in, streamed text out, no tools. The
 * implementations are small clients in `:ai`, over `:net` (D3).
 */
interface AiProvider {
    val id: ProviderId

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
 */
data class CompletionRequest(
    val model: String,
    val prompt: String,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val effort: ReasoningEffort? = null,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive, was $maxTokens" }
    }

    companion object {
        /**
         * Room for a card of a few sentences or paragraphs and for the reasoning that current
         * models do by default before it; a reply that reaches it ends with [StopReason.MAX_TOKENS].
         */
        const val DEFAULT_MAX_TOKENS: Int = 4096
    }
}

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

    /** The reply is complete. */
    data class Done(
        val usage: Usage,
        val stopReason: StopReason,
    ) : CompletionEvent

    /**
     * The reply failed; the text so far, if any, is incomplete. [retryAfter] is the service's
     * requested wait, when it gave one.
     */
    data class Error(
        val kind: AiErrorKind,
        val retryAfter: Duration? = null,
    ) : CompletionEvent
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

    /** Too many requests; try again later. */
    RATE_LIMITED,

    /** The service is overloaded; try again later. */
    OVERLOADED,

    /** The service failed. */
    SERVER,

    /** The model does not exist, or the key cannot use it. */
    MODEL_NOT_FOUND,

    /** The service refused the request as malformed. */
    INVALID_REQUEST,

    /** The prompt is too large for the service. */
    REQUEST_TOO_LARGE,

    /** The stream broke off or ended before the reply was complete. */
    INCOMPLETE,

    /** The service answered with something this client cannot read. */
    UNEXPECTED_RESPONSE,
}

/** A model a provider offers. [createdAt] is its release, when the service says. */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val createdAt: Instant?,
)

/** The result of [AiProvider.models]. */
sealed interface ModelList {
    data class Listed(
        val models: List<ModelInfo>,
    ) : ModelList

    data class Failed(
        val kind: AiErrorKind,
    ) : ModelList
}
