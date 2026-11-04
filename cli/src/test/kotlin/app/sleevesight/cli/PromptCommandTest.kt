package app.sleevesight.cli

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptCommandTest {
    private val dir = createTempDirectory("prompt").toFile()
    private val prompt = File(dir, "prompt.txt").apply { writeText("Write a card.\nTwo lines.") }
    private val system = File(dir, "system.txt").apply { writeText("You narrate.") }

    // No keys file: the key comes from the environment alone.
    private val noFile = File(dir, "no-keys.env").path

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A stand-in service that answers each request with [events], keeping the requests. */
    private class Answering(
        private val events: List<CompletionEvent>,
    ) : AiProvider {
        val requests = mutableListOf<CompletionRequest>()
        val keys = mutableListOf<String>()
        override val id = ProviderId.ANTHROPIC

        override suspend fun models(key: Secret): ModelList = ModelList.Listed(emptyList())

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> {
            requests += request
            keys += key.reveal()
            return flowOf(*events.toTypedArray())
        }
    }

    private class Run(
        val status: Int,
        val out: String,
        val err: String,
    )

    private fun run(
        provider: AiProvider,
        vararg args: String,
        environment: Map<String, String> = mapOf(Keys.ANTHROPIC to "sk-ant-test"),
    ): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status =
            PromptCommand(
                PrintStream(out, true),
                PrintStream(err, true),
                environment,
                ByteArrayInputStream(ByteArray(0)),
            ) { provider }.run(args.toList() + listOf("--keys", noFile))
        return Run(status, out.toString(), err.toString())
    }

    @Test
    fun `the defaults are the app's model, effort and token limit for an insight`() {
        val options = PromptOptions.parse(listOf("--file", prompt.path))
        assertEquals(ProviderId.ANTHROPIC, options.provider)
        assertEquals("claude-haiku-5-5", options.model)
        assertEquals(ReasoningEffort.LOW, options.effort)
        assertEquals(4096, options.maxTokens)
        assertEquals(1, options.repeat)
        assertNull(options.system)
        assertEquals(Keys.DEFAULT_FILE, options.keysFile)

        val google = PromptOptions.parse(listOf("--file", prompt.path, "--provider", "google", "--effort", "high"))
        assertEquals("gemini-3.5-flash-lite", google.model)
        assertEquals(16384, google.maxTokens)
        assertNull(PromptOptions.parse(listOf("--file", prompt.path, "--effort", "default")).effort)
    }

    @Test
    fun `a bad command line is refused`() {
        for (args in listOf(
            emptyList(),
            listOf("--file", File(dir, "missing.txt").path),
            listOf("--file", prompt.path, "--provider", "mistral"),
            listOf("--file", prompt.path, "--effort", "max"),
            listOf("--file", prompt.path, "--repeat", "0"),
            listOf("--file", prompt.path, "--max-tokens", "many"),
            listOf("--file", prompt.path, "--tracks", "1"),
            listOf("--file", prompt.path, "--system", "-"),
        )) {
            assertFailsWith<IllegalArgumentException>("$args") { PromptOptions.parse(args) }
        }
    }

    @Test
    fun `each reply is printed with its tokens and stop reason, the request as given`() {
        val provider =
            Answering(listOf(CompletionEvent.Delta("Hello "), CompletionEvent.Delta("there."), done))
        val run =
            run(
                provider,
                "--file",
                prompt.path,
                "--system",
                system.path,
                "--model",
                "m",
                "--max-tokens",
                "100",
                "--repeat",
                "2",
            )

        assertEquals(0, run.status)
        val sent =
            CompletionRequest("m", "Write a card.\nTwo lines.", 100, ReasoningEffort.LOW, system = "You narrate.")
        assertEquals(listOf(sent), provider.requests.distinct())
        assertEquals(2, provider.requests.size)
        assertEquals(listOf("sk-ant-test", "sk-ant-test"), provider.keys)
        assertTrue(run.out.startsWith("--- reply 1/2\nHello there.\n--- input 12 tokens, output 3, stop reason end, "))
        assertTrue("--- reply 2/2\nHello there.\n" in run.out)
        assertFalse("sk-ant" in run.out + run.err)
    }

    @Test
    fun `a failed call is reported, and no key is a usage error`() {
        val failing = run(Answering(listOf(CompletionEvent.Error(AiErrorKind.RATE_LIMITED))), "--file", prompt.path)
        assertEquals(Cli.EXIT_UNCHECKED, failing.status)
        assertTrue("--- error rate_limited, " in failing.out)

        val keyless = run(Answering(listOf(done)), "--file", prompt.path, environment = emptyMap())
        assertEquals(Cli.EXIT_USAGE, keyless.status)
        assertTrue(Keys.ANTHROPIC in keyless.err)
    }

    private companion object {
        val done = CompletionEvent.Done(Usage(12, 3), StopReason.END)
    }
}
