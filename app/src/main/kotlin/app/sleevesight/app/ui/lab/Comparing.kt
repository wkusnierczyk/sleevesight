package app.sleevesight.app.ui.lab

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sleevesight.app.R
import app.sleevesight.app.lab.PromptLab
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.synthesis.LabComparison
import app.sleevesight.core.synthesis.LabMark
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.LabSettings
import app.sleevesight.core.synthesis.LabVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock

/** Why a version could not be written, as its line names it. */
internal enum class Failure {
    TIMED_OUT,
    UNREACHED,
    SEARCHED,
    OTHER,
}

/**
 * One version of a comparison: the number it is [shown] under, its variant's [id] and the
 * [instructions] it is written with (none for the release prompt's); what it wrote ([text]) or why
 * not ([failed]), whether it is still [writing], and the tester's [mark] and [note].
 */
internal class VersionState(
    val shown: Int,
    val id: String,
    val instructions: String?,
) {
    var text by mutableStateOf<String?>(null)
    var failed by mutableStateOf<String?>(null)
    var writing by mutableStateOf(false)
    var mark by mutableStateOf<LabMark?>(null)
    var note by mutableStateOf("")
    var noting by mutableStateOf(false)
    var job: Job? = null
}

/**
 * A comparison under way (#1003): [subject]'s request as it was sent ([sent]; null when none was
 * since the app started, and nothing is written), written again with each variant taking part, in
 * shuffled order ([versions]), each a paid call of its own in [scope], with its tools left out, so
 * every version is written from the evidence given alone. Its prompts' names are [revealed] by
 * *Show the prompts*, which logs it ([logged]).
 */
internal class Comparing private constructor(
    val subject: LabSubject,
    val sent: PromptLab.Sent?,
    val versions: List<VersionState>,
    private val scope: CoroutineScope,
    private val failure: (Failure) -> String,
    private val stopped: String,
) {
    var revealed by mutableStateOf(false)

    /** Whether any version is still being written. */
    val writing: Boolean get() = versions.any { it.writing }

    /** The model the versions are written by: the one the insight's request named. */
    val model: String get() = sent?.request?.model.orEmpty()

    /** Writes [version] again, after it failed or was stopped. */
    fun retry(version: VersionState) {
        val sent = sent ?: return
        version.failed = null
        version.text = null
        write(sent, version)
    }

    /** Stops every version still being written; each says it was stopped. */
    fun stop() {
        for (version in versions) {
            if (version.writing) {
                version.job?.cancel()
                version.writing = false
                version.failed = stopped
            }
        }
    }

    /** The comparison as logged, at [now], by the app's version [app]. */
    fun logged(
        app: String,
        now: kotlin.time.Instant = Clock.System.now(),
    ): LabComparison {
        val request = sent?.request
        return LabComparison(
            time = now,
            app = app,
            kind = subject.kind,
            title = subject.title,
            artist = subject.artist,
            album = subject.album,
            service =
                sent
                    ?.provider
                    ?.id
                    ?.name
                    .orEmpty(),
            model = request?.model.orEmpty(),
            effort = request?.effort?.name?.lowercase(),
            evidence = request?.let { if (it.system != null) it.prompt else it.prompt.substringAfter('\n') }.orEmpty(),
            instructions =
                versions.associate { v ->
                    v.id to (request?.let { LabPrompts.opening(withVariant(it, v)) } ?: "")
                },
            versions =
                versions.map {
                    LabVersion(it.shown, it.id, it.text, failed = it.failed, mark = it.mark, note = it.note.trim())
                },
        )
    }

    private fun write(
        sent: PromptLab.Sent,
        version: VersionState,
    ) {
        version.writing = true
        version.job =
            scope.launch {
                val reply = StringBuilder()
                try {
                    sent.provider
                        .stream(
                            withVariant(sent.request, version).copy(tools = emptyList()),
                            sent.key,
                        ).collect { event ->
                            when (event) {
                                is CompletionEvent.Delta -> reply.append(event.text)
                                is CompletionEvent.Done ->
                                    version.text =
                                        LabPrompts.shownReply(subject.kind, reply.toString())
                                is CompletionEvent.ToolUse -> version.failed = failure(Failure.SEARCHED)
                                is CompletionEvent.Error -> version.failed = failure(failureOf(event.kind))
                            }
                        }
                } finally {
                    version.writing = false
                }
            }
    }

    private fun withVariant(
        request: app.sleevesight.core.ai.CompletionRequest,
        version: VersionState,
    ) = version.instructions?.let { LabPrompts.withInstructions(request, it) } ?: request

    companion object {
        /**
         * A comparison of [subject] from [sent], with each variant [settings] has taking part, in an
         * order shuffled by [random], each written at once in [scope]; nothing is written without
         * [sent].
         */
        @Suppress("LongParameterList") // What it compares, from what, with what, where, and its words.
        fun start(
            subject: LabSubject,
            sent: PromptLab.Sent?,
            settings: LabSettings,
            scope: CoroutineScope,
            failure: (Failure) -> String,
            stopped: String,
            random: Random = Random.Default,
        ): Comparing {
            val ids = settings.compared(subject.kind).shuffled(random)
            val versions = ids.mapIndexed { i, id -> VersionState(i + 1, id, settings.instructions(subject.kind, id)) }
            val run = Comparing(subject, sent, if (sent == null) emptyList() else versions, scope, failure, stopped)
            if (sent != null) versions.forEach { run.write(sent, it) }
            return run
        }

        private fun failureOf(kind: AiErrorKind): Failure =
            when (kind) {
                AiErrorKind.TIMED_OUT, AiErrorKind.CONNECT_TIMED_OUT -> Failure.TIMED_OUT
                AiErrorKind.OFFLINE, AiErrorKind.CONNECTION_FAILED, AiErrorKind.CONNECT_FAILED -> Failure.UNREACHED
                else -> Failure.OTHER
            }
    }
}

/**
 * [credit], a text's credit line ("Written by Claude Haiku 5.5."), with the lab's variant [id] joined
 * to it (#1003): "Written by Claude Haiku 5.5 · prompt B."
 */
internal fun labCreditText(
    credit: String,
    id: String,
    resources: Resources,
): String {
    val name = if (id == LabPrompts.CUSTOM) resources.getString(R.string.lab_variant_custom) else id
    return resources.getString(R.string.lab_credit, credit.removeSuffix("."), name)
}
