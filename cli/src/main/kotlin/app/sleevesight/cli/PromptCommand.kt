package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.net.NetClient
import app.sleevesight.wiring.HostPolicies
import app.sleevesight.wiring.pipelineWiringOn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import kotlin.time.TimeSource

/**
 * `prompt` (#767): sends one prompt, and a system prompt if given, to an AI service, through the
 * client the app and a live run use (`:wiring`'s), and prints each reply as it comes, then its tokens
 * and stop reason: for trying other prompts for the input a debug build logged (`PromptDump` in the
 * app). The keys are read as a live run reads them ([Keys]). Not a pipeline run: no sources, no
 * grounding check, no cache. Every request is a paid call. See [USAGE].
 *
 * The replies are printed on [out], everything else on [err]. [client] is the service's client;
 * by default the app's, on a live `NetClient` of its own; a test gives a stand-in.
 */
class PromptCommand(
    private val out: PrintStream,
    private val err: PrintStream,
    private val environment: Map<String, String>,
    private val input: InputStream,
    private val client: ((ProviderId) -> AiProvider)? = null,
) {
    /** What stops the command before anything is sent, said as [message]. */
    private class Refused(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    fun run(args: List<String>): Int {
        val prepared =
            try {
                prepare(args)
            } catch (e: IllegalArgumentException) {
                err.println(e.message)
                err.println(USAGE)
                null
            } catch (e: Refused) {
                err.println(e.message)
                null
            }
        val (options, request, key) = prepared ?: return Cli.EXIT_USAGE
        val named = client
        return if (named != null) {
            send(named(options.provider), request, key, options.repeat)
        } else {
            NetClient(HostPolicies.netConfig(Cli.APP_VERSION)).use { net ->
                val wiring = pipelineWiringOn(net, JvmAccentFolder, JvmResourceReader)
                send(wiring.aiProviders.getValue(options.provider), request, key, options.repeat)
            }
        }
    }

    // The options, the request they make and the service's key; throws for a bad command line, a
    // file that cannot be read, or no key.
    private fun prepare(args: List<String>): Triple<PromptOptions, CompletionRequest, Secret> {
        val options = PromptOptions.parse(args)
        val (prompt, system) =
            try {
                read(options.file) to options.system?.let(::read)
            } catch (e: IOException) {
                throw Refused("The prompt could not be read: ${e.message}", e)
            }
        val key =
            Keys.load(environment, options.keysFile).forProvider(options.provider)
                ?: throw Refused("No ${Keys.variableOf(options.provider)}, in the environment or the keys file.")
        val request = CompletionRequest(options.model, prompt, options.maxTokens, options.effort, system = system)
        return Triple(options, request, key)
    }

    // Sends [request] [repeat] times, one after another; 0 when every reply ended, else 3.
    private fun send(
        provider: AiProvider,
        request: CompletionRequest,
        key: Secret,
        repeat: Int,
    ): Int {
        val effort = request.effort?.name?.lowercase() ?: "default"
        err.println(
            "${Options.nameOf(provider.id)} ${request.model}, effort $effort, " +
                "max tokens ${request.maxTokens}; prompt ${request.prompt.length} characters" +
                (request.system?.let { ", system prompt ${it.length}" } ?: ", no system prompt"),
        )
        var failed = 0
        for (n in 1..repeat) {
            out.println("--- reply $n/$repeat")
            val started = TimeSource.Monotonic.markNow()
            val end =
                runBlocking {
                    var end: CompletionEvent? = null
                    provider.stream(request, key).collect { event ->
                        when (event) {
                            is CompletionEvent.Delta -> {
                                out.print(event.text)
                                out.flush()
                            }

                            is CompletionEvent.Done, is CompletionEvent.Error -> end = event
                            is CompletionEvent.ToolUse -> Unit
                        }
                    }
                    end
                }
            out.println()
            val ms = started.elapsedNow().inWholeMilliseconds
            out.println("--- ${describe(end)}, $ms ms")
            if (end !is CompletionEvent.Done) failed++
        }
        return if (failed == 0) 0 else Cli.EXIT_UNCHECKED
    }

    // How a reply ended: its tokens and stop reason, or its error.
    private fun describe(end: CompletionEvent?): String =
        when (end) {
            is CompletionEvent.Done -> {
                "input ${end.usage.inputTokens} tokens, output ${end.usage.outputTokens}, " +
                    "stop reason ${end.stopReason.name.lowercase()}"
            }

            is CompletionEvent.Error -> {
                "error ${end.kind.name.lowercase()}" +
                    (end.retryAfter?.let { ", retry after $it" } ?: "") +
                    (
                        end.breakoff?.let {
                            ", ${it.cause.name.lowercase()} after input ${it.usage.inputTokens} tokens, " +
                                "output ${it.usage.outputTokens}" + (it.detail?.let { detail -> " ($detail)" } ?: "")
                        } ?: ""
                    )
            }

            else -> {
                "no end"
            }
        }

    // The prompt in [path], or on standard input for `-`, as it is.
    private fun read(path: String): String =
        if (path == "-") input.bufferedReader().readText() else File(path).readText()

    companion object {
        val USAGE: String =
            """
            Usage: sleevesight-cli prompt --file <file|-> [--system <file>] [--provider <name>] [--model <id>]
                                          [--effort <level>] [--max-tokens <n>] [--repeat <n>] [--keys <file>]

              Sends the prompt in --file (- for standard input), as the one user message, to an AI
              service, through the client the app uses, and prints each reply as it comes, then its
              input and output tokens and stop reason. A paid call each time. Not a pipeline run: no
              sources, no grounding check, no cache. For the prompts a debug build logs
              (adb logcat -s SleevesightPrompt -v raw).

              --system <file>     the system prompt (default: none, as the app sends today)
              --provider <name>   anthropic, openai or google (default: anthropic)
              --model <id>        the provider's model (default: the app's default for it)
              --effort <level>    minimal, low, medium, high, or default for the service's own
                                  (default: the app's default for the provider, low)
              --max-tokens <n>    the reply's limit, its reasoning included (default: the app's
                                  for an insight at the effort: 4096 at low, 8192 at medium, 16384
                                  at high or the service's default)
              --repeat <n>        send it n times, one after another (default 1)
              --keys <file>       the keys file (default: ~/.config/sleevesight/keys.env), read as
                                  live reads it

            Exit status: 0 when every reply ended; 2 for a usage error, or no key; 3 when a call failed.
            """.trimIndent()
    }
}

/** `prompt`'s command line, parsed, with the app's defaults filled in. */
data class PromptOptions(
    val file: String,
    val system: String?,
    val provider: ProviderId,
    val model: String,
    val effort: ReasoningEffort?,
    val maxTokens: Int,
    val repeat: Int,
    val keysFile: File,
) {
    companion object {
        private val VALUED =
            setOf(
                "--file",
                "--system",
                "--provider",
                "--model",
                "--effort",
                "--max-tokens",
                "--repeat",
                "--keys",
            )

        /** Parses [args]; throws [IllegalArgumentException] with the reason for a bad command line. */
        fun parse(args: List<String>): PromptOptions {
            val values = mutableMapOf<String, String>()
            val rest = args.iterator()
            while (rest.hasNext()) {
                val arg = rest.next()
                require(arg in VALUED) { "Unknown option $arg" }
                require(rest.hasNext()) { "$arg needs a value" }
                values[arg] = rest.next()
            }
            val file = requireNotNull(values["--file"]) { "--file is needed: the prompt to send" }
            require(readable(file)) { "--file: no such file" }
            values["--system"]?.let { require(it != "-" && readable(it)) { "--system: no such file" } }
            val provider =
                values["--provider"]?.let { name ->
                    requireNotNull(Options.PROVIDERS[name]) {
                        "--provider takes ${Options.PROVIDERS.keys.joinToString()}"
                    }
                } ?: ProviderId.ANTHROPIC
            val effort =
                when (val level = values["--effort"]) {
                    null -> Settings().effort(provider)
                    "default" -> null
                    else -> {
                        val named = ReasoningEffort.entries.firstOrNull { it.name.equals(level, ignoreCase = true) }
                        requireNotNull(named) {
                            "--effort takes ${ReasoningEffort.entries.joinToString { it.name.lowercase() }} or default"
                        }
                    }
                }
            return PromptOptions(
                file = file,
                system = values["--system"],
                provider = provider,
                model = values["--model"] ?: Settings.DEFAULT_MODELS.getValue(provider),
                effort = effort,
                maxTokens = positive(values, "--max-tokens") ?: CompletionRequest.maxTokensFor(effort),
                repeat = positive(values, "--repeat") ?: 1,
                keysFile = values["--keys"]?.let(::File) ?: Keys.DEFAULT_FILE,
            )
        }

        // The number [name] gives, 1 or more, or null when it is not given.
        private fun positive(
            values: Map<String, String>,
            name: String,
        ): Int? =
            values[name]?.let {
                val n = requireNotNull(it.toIntOrNull()) { "$name takes a number" }
                require(n >= 1) { "$name takes 1 or more, was $n" }
                n
            }
    }
}
