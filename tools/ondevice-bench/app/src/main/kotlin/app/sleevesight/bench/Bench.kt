@file:OptIn(ExperimentalApi::class)

package app.sleevesight.bench

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class Model(
    val label: String,
    val file: String,
) {
    E4B("Gemma 4 E4B", "gemma-4-E4B-it.litertlm"),
    E2B("Gemma 4 E2B", "gemma-4-E2B-it.litertlm"),
}

enum class BackendChoice { GPU, CPU }

enum class RunSet(
    val label: String,
) {
    LOAD("Load only: cold (cache cleared), then warm"),
    ONE("One prompt (the longest)"),
    TEN("Set of 10 (short to long)"),
    TEN_X3("Set of 10, three times (heat)"),
    ALL("All 77 prompts (for the grounding check)"),
}

/** One prompt from `assets/prompts.json` (`mac/bench.sh prompts`). */
data class Prompt(
    val key: String,
    val n: Int,
    val title: String,
    val user: String,
    val chars: Int,
)

class Prompts(
    val version: Int,
    val system: String,
    val all: List<Prompt>,
) {
    /** Ten prompts spread evenly over the lengths, shortest to longest (by characters). */
    val ten: List<Prompt>
        get() {
            val sorted = all.sortedBy { it.chars }
            return (0 until 10).map { sorted[Math.round(it * (sorted.size - 1) / 9.0).toInt()] }.distinct()
        }

    val longest: Prompt get() = all.maxBy { it.chars }

    companion object {
        fun load(context: Context): Prompts {
            val doc =
                JSONObject(
                    context.assets
                        .open("prompts.json")
                        .bufferedReader()
                        .readText(),
                )
            val list = doc.getJSONArray("prompts")
            val prompts =
                (0 until list.length()).map {
                    val p = list.getJSONObject(it)
                    Prompt(
                        p.getString("key"),
                        p.getInt("n"),
                        p.getString("title"),
                        p.getString("user"),
                        p.getInt("chars"),
                    )
                }
            return Prompts(doc.getInt("promptVersion"), doc.getString("system"), prompts)
        }
    }
}

/**
 * One bench run: loads [model] on [backend], runs [set], samples the phone every two seconds, and
 * writes everything to [out]. [log] gets one line per step; [stop] ends it after the current reply
 * (cancelling it).
 */
class Bench(
    private val context: Context,
    private val model: Model,
    private val backend: BackendChoice,
    private val set: RunSet,
    private val out: File,
    private val log: (String) -> Unit,
    private val stop: AtomicBoolean,
) {
    private val probe = Probe(context)
    private val phase = AtomicReference("start")
    private val samples = mutableListOf<Reading>()
    private val running = AtomicBoolean(true)
    private val current = AtomicReference<com.google.ai.edge.litertlm.Conversation?>(null)

    private val modelFile = File(modelDir(context), model.file)
    private val cacheDir = File(context.filesDir, "weightcache/${model.name}-${backend.name}")

    fun run() {
        out.mkdirs()
        // Resets this process's peak resident set (VmHWM), so the peak is this run's, not an earlier one's.
        val peakReset = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        if (!peakReset) log("Could not reset the peak RSS: close the app between runs for a per-run peak.")
        val prompts = Prompts.load(context)
        val start = probe.read("start", withPss = true)
        writeDevice(prompts, start)
        if (start.plugged) log("WARNING: the phone is charging; battery figures will be meaningless.")
        val sampler = Thread({ sample() }, "bench-sampler").apply { start() }
        val summary = JSONObject()
        try {
            if (!modelFile.isFile) {
                log("No model file at ${modelFile.path}. Push it with adb first (see the run sheet).")
                return
            }
            ExperimentalFlags.enableBenchmark = true
            val loads = JSONArray()
            if (set == RunSet.LOAD) {
                // Cold: no weight cache, neither ours nor one the runtime may have put beside the model.
                cacheDir.deleteRecursively()
                modelFile.parentFile
                    ?.listFiles()
                    ?.filter {
                        !it.name.endsWith(
                            ".litertlm",
                        )
                    }?.forEach { it.deleteRecursively() }
                for (label in listOf("cold", "warm")) {
                    if (stop.get()) break
                    val (engine, load) = load(label)
                    loads.put(load)
                    engine.close()
                }
            } else {
                val (engine, load) = load(if (dirSize(cacheDir) == 0L) "cold" else "warm")
                loads.put(load)
                try {
                    summary.put("runs", runPrompts(engine, prompts))
                } finally {
                    engine.close()
                }
            }
            summary.put("loads", loads)
        } catch (t: Throwable) {
            log("FAILED: $t")
            summary.put("error", t.toString())
        } finally {
            phase.set("end")
            running.set(false)
            sampler.join()
            val end = probe.read("end", withPss = true)
            synchronized(samples) { samples.add(end) }
            writeSamples()
            summary.put("phone", phoneSummary(start, end))
            File(out, "summary.json").writeText(summary.toString(2))
            log("Done. Results in ${out.path}")
            log(shortSummary(summary))
        }
    }

    private fun load(label: String): Pair<Engine, JSONObject> {
        phase.set("load-$label")
        cacheDir.mkdirs()
        val cacheBefore = dirSize(cacheDir)
        val besideBefore = besideModel()
        log("Loading ${model.label} on $backend ($label, cache ${mb(cacheBefore)})…")
        val t0 = SystemClock.elapsedRealtime()
        val config =
            EngineConfig(
                modelPath = modelFile.path,
                backend = if (backend == BackendChoice.GPU) Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDir.path,
            )
        val engine = Engine(config)
        engine.initialize()
        val ms = SystemClock.elapsedRealtime() - t0
        val after = probe.read("loaded-$label", withPss = true)
        synchronized(samples) { samples.add(after) }
        val load =
            JSONObject()
                .put("label", label)
                .put("ms", ms)
                .put("cacheBeforeBytes", cacheBefore)
                .put("cacheAfterBytes", dirSize(cacheDir))
                .put("besideModelBeforeBytes", besideBefore)
                .put("besideModelAfterBytes", besideModel())
                .put("vmRssKbAfter", after.vmRssKb)
                .put("pssKbAfter", after.pssKb)
        log(
            "Loaded in ${"%.1f".format(
                ms / 1000.0,
            )} s; weight cache ${mb(dirSize(cacheDir))} (beside the model: ${mb(besideModel())}).",
        )
        return engine to load
    }

    private fun runPrompts(
        engine: Engine,
        prompts: Prompts,
    ): JSONArray {
        val chosen =
            when (set) {
                RunSet.ONE -> listOf(prompts.longest)
                RunSet.TEN -> prompts.ten
                RunSet.TEN_X3 -> prompts.ten + prompts.ten + prompts.ten
                RunSet.ALL -> prompts.all
                RunSet.LOAD -> emptyList()
            }
        val runsCsv = File(out, "runs.csv")
        runsCsv.writeText(RUN_HEADER + "\n")
        val replies = File(out, "replies.jsonl")
        val rows = JSONArray()
        for ((i, prompt) in chosen.withIndex()) {
            if (stop.get()) {
                log("Stopped.")
                break
            }
            val round = if (set == RunSet.TEN_X3) i / prompts.ten.size + 1 else 1
            phase.set("reply-${i + 1}")
            val row = runOne(engine, prompts.system, prompt, i + 1, round)
            rows.put(row)
            runsCsv.appendText(RUN_COLUMNS.joinToString(",") { csvValue(row.opt(it)) } + "\n")
            replies.appendText(
                JSONObject()
                    .put("run", "${i + 1}")
                    .put("round", round)
                    .put("key", prompt.key)
                    .put("n", prompt.n)
                    .put("model", model.name)
                    .put("backend", backend.name)
                    .put("reply", row.optString("replyText"))
                    .toString() + "\n",
            )
            row.remove("replyText")
            log(
                "#${i + 1} track ${prompt.n}: prefill ${row.opt("prefillTokens")} tok at " +
                    "${fmt(row.optDouble("prefillTokPerS"))} tok/s, first token ${fmt(row.optDouble("ttftS"))} s, " +
                    "decode ${row.opt("decodeTokens")} tok at ${fmt(row.optDouble("decodeTokPerS"))} tok/s, " +
                    "total ${fmt(row.optLong("totalMs") / 1000.0)} s" +
                    (row.optString("error").takeIf { it.isNotEmpty() }?.let { " ERROR $it" } ?: ""),
            )
        }
        return rows
    }

    private fun runOne(
        engine: Engine,
        system: String,
        prompt: Prompt,
        index: Int,
        round: Int,
    ): JSONObject {
        val before = probe.read("before-$index", withPss = false)
        val config =
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE, seed = SEED),
                maxOutputToken = MAX_OUTPUT,
            )
        val text = StringBuilder()
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        var firstMs = -1L
        var chunks = 0
        val conversation = engine.createConversation(config)
        current.set(conversation)
        val t0 = SystemClock.elapsedRealtime()
        try {
            conversation.sendMessageAsync(
                prompt.user,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (firstMs < 0) firstMs = SystemClock.elapsedRealtime() - t0
                        chunks++
                        text.append(textOf(message))
                    }

                    override fun onDone() = done.countDown()

                    override fun onError(throwable: Throwable) {
                        error.set(throwable)
                        done.countDown()
                    }
                },
            )
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (stop.get()) conversation.cancelProcess()
            }
            val totalMs = SystemClock.elapsedRealtime() - t0
            val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            val after = probe.read("after-$index", withPss = false)
            return JSONObject()
                .put("index", index)
                .put("round", round)
                .put("key", prompt.key)
                .put("n", prompt.n)
                .put("promptChars", prompt.chars)
                .put("prefillTokens", info?.lastPrefillTokenCount ?: -1)
                .put("prefillTokPerS", num(info?.lastPrefillTokensPerSecond))
                .put("ttftS", num(info?.timeToFirstTokenInSecond))
                .put("ttftWallMs", firstMs)
                .put("decodeTokens", info?.lastDecodeTokenCount ?: -1)
                .put("decodeTokPerS", num(info?.lastDecodeTokensPerSecond))
                .put("totalMs", totalMs)
                .put("chunks", chunks)
                .put("replyChars", text.length)
                .put("batteryPctBefore", before.batteryPercent)
                .put("batteryPctAfter", after.batteryPercent)
                .put("chargeUahBefore", before.chargeCounterUah)
                .put("chargeUahAfter", after.chargeCounterUah)
                .put("batteryTempBefore", num(before.batteryTempC))
                .put("batteryTempAfter", num(after.batteryTempC))
                .put("thermalBefore", before.thermalStatus)
                .put("thermalAfter", after.thermalStatus)
                .put("headroomAfter", num(after.thermalHeadroom.toDouble()))
                .put("vmHwmKb", after.vmHwmKb)
                .put("error", error.get()?.toString() ?: "")
                .put("replyText", text.toString())
        } finally {
            current.set(null)
            conversation.close()
        }
    }

    private fun textOf(message: Message): String =
        message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    /** Samples every two seconds while the run lasts; memory (PSS) every other sample. */
    private fun sample() {
        var tick = 0
        while (running.get()) {
            val reading = runCatching { probe.read(phase.get(), withPss = tick % 2 == 0) }.getOrNull()
            if (reading != null) synchronized(samples) { samples.add(reading) }
            tick++
            var slept = 0
            while (running.get() && slept < SAMPLE_MS) {
                Thread.sleep(100)
                slept += 100
            }
        }
    }

    private fun writeSamples() {
        val lines = synchronized(samples) { samples.map { it.csv() } }
        File(out, "samples.csv").writeText(Reading.HEADER + "\n" + lines.joinToString("\n") + "\n")
    }

    private fun phoneSummary(
        start: Reading,
        end: Reading,
    ): JSONObject {
        val all = synchronized(samples) { samples.toList() }
        return JSONObject()
            .put("seconds", (end.elapsedMs - start.elapsedMs) / 1000.0)
            .put("batteryPctStart", start.batteryPercent)
            .put("batteryPctEnd", end.batteryPercent)
            .put("chargeUahUsed", start.chargeCounterUah - end.chargeCounterUah)
            .put("batteryTempStart", num(start.batteryTempC))
            .put("batteryTempEnd", num(end.batteryTempC))
            .put("batteryTempMax", num(all.maxOfOrNull { it.batteryTempC }))
            .put("thermalStatusStart", start.thermalStatus)
            .put("thermalStatusMax", all.maxOfOrNull { it.thermalStatus } ?: -1)
            .put("thermalStatusEnd", end.thermalStatus)
            .put(
                "thermalHeadroomMax",
                num(
                    all
                        .mapNotNull {
                            it.thermalHeadroom.toDouble().takeIf { h ->
                                !h.isNaN()
                            }
                        }.maxOrNull(),
                ),
            ).put("vmHwmKbPeak", all.maxOfOrNull { it.vmHwmKb } ?: -1)
            .put("pssKbPeak", all.maxOfOrNull { it.pssKb } ?: -1)
            .put("nativePssKbPeak", all.maxOfOrNull { it.nativePssKb } ?: -1)
            .put("graphicsKbPeak", all.maxOfOrNull { it.graphicsKb } ?: -1)
            .put("nativeHeapKbPeak", all.maxOfOrNull { it.nativeHeapKb } ?: -1)
            .put("pluggedAtAnyPoint", all.any { it.plugged })
    }

    private fun shortSummary(summary: JSONObject): String =
        buildString {
            summary.optJSONArray("loads")?.let { loads ->
                for (i in 0 until loads.length()) {
                    val l = loads.getJSONObject(i)
                    appendLine(
                        "Load ${l.getString(
                            "label",
                        )}: ${fmt(l.getLong("ms") / 1000.0)} s, cache ${mb(l.getLong("cacheAfterBytes"))}",
                    )
                }
            }
            summary.optJSONArray("runs")?.let { runs ->
                val rows =
                    (0 until runs.length())
                        .map {
                            runs.getJSONObject(
                                it,
                            )
                        }.filter { it.optString("error").isEmpty() }
                if (rows.isNotEmpty()) {
                    appendLine("Replies: ${rows.size}")
                    appendLine("Prefill tok/s, median: ${fmt(median(rows.map { it.optDouble("prefillTokPerS") }))}")
                    appendLine("Decode tok/s, median: ${fmt(median(rows.map { it.optDouble("decodeTokPerS") }))}")
                    appendLine("First token s, median: ${fmt(median(rows.map { it.optDouble("ttftS") }))}")
                    appendLine("Total s, median: ${fmt(median(rows.map { it.optLong("totalMs") / 1000.0 }))}")
                }
            }
            summary.optJSONObject("phone")?.let { p ->
                appendLine(
                    "Battery ${p.optInt("batteryPctStart")} → ${p.optInt("batteryPctEnd")} % " +
                        "(${p.optLong("chargeUahUsed") / 1000} mAh), temp ${p.optDouble("batteryTempStart")} → " +
                        "${p.optDouble("batteryTempEnd")} °C (max ${p.optDouble("batteryTempMax")})",
                )
                appendLine(
                    "Thermal status ${p.optInt("thermalStatusStart")} → max ${p.optInt("thermalStatusMax")}; " +
                        "peak RSS ${p.optLong("vmHwmKbPeak") / 1024} MB, peak PSS ${p.optLong("pssKbPeak") / 1024} MB",
                )
            }
        }

    private fun writeDevice(
        prompts: Prompts,
        start: Reading,
    ) {
        val brightness =
            runCatching {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                )
            }.getOrDefault(-1)
        val autoBrightness =
            runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            }.getOrDefault(-1)
        val device =
            JSONObject()
                .put("started", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()))
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("socModel", Build.SOC_MODEL)
                .put("socManufacturer", Build.SOC_MANUFACTURER)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("runtime", "com.google.ai.edge.litertlm:litertlm-android:$RUNTIME_VERSION")
                .put("modelName", model.name)
                .put("modelFile", modelFile.path)
                .put("modelBytes", modelFile.length())
                .put("backend", backend.name)
                .put("set", set.name)
                .put("promptVersion", prompts.version)
                .put("prompts", prompts.all.size)
                .put("maxNumTokens", MAX_NUM_TOKENS)
                .put("temperature", TEMPERATURE)
                .put("topK", TOP_K)
                .put("topP", TOP_P)
                .put("seed", SEED)
                .put("maxOutputTokens", MAX_OUTPUT)
                .put("screenBrightness", brightness)
                .put("screenBrightnessAuto", autoBrightness == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                .put("pluggedAtStart", start.plugged)
                .put("batteryPctAtStart", start.batteryPercent)
                .put("thermalStatusAtStart", start.thermalStatus)
        File(out, "device.json").writeText(device.toString(2))
    }

    /** Files the runtime may write next to the model (a weight cache when it ignores [cacheDir]). */
    private fun besideModel(): Long =
        modelFile.parentFile
            ?.listFiles()
            ?.filter { it.name != Model.E2B.file && it.name != Model.E4B.file }
            ?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

    companion object {
        const val RUNTIME_VERSION = "0.19.1"
        const val TEMPERATURE = 0.6
        const val TOP_K = 64
        const val TOP_P = 0.95
        const val SEED = 272
        const val MAX_OUTPUT = 4096

        /** The context: the longest prompt (about 1,600 tokens) plus [MAX_OUTPUT], with room to spare. */
        const val MAX_NUM_TOKENS = 8192
        const val SAMPLE_MS = 2000

        private val RUN_COLUMNS =
            listOf(
                "index",
                "round",
                "key",
                "n",
                "promptChars",
                "prefillTokens",
                "prefillTokPerS",
                "ttftS",
                "ttftWallMs",
                "decodeTokens",
                "decodeTokPerS",
                "totalMs",
                "chunks",
                "replyChars",
                "batteryPctBefore",
                "batteryPctAfter",
                "chargeUahBefore",
                "chargeUahAfter",
                "batteryTempBefore",
                "batteryTempAfter",
                "thermalBefore",
                "thermalAfter",
                "headroomAfter",
                "vmHwmKb",
                "error",
            )
        private val RUN_HEADER = RUN_COLUMNS.joinToString(",")

        fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), "models")

        fun resultsDir(context: Context): File = File(context.getExternalFilesDir(null), "results")

        fun runName(
            model: Model,
            backend: BackendChoice,
            set: RunSet,
        ): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) +
                "-${model.name}-${backend.name}-${set.name}"

        fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_048_576.0)

        /** JSON has no NaN: a missing number is null. */
        fun num(value: Double?): Any =
            if (value == null ||
                value.isNaN() ||
                value.isInfinite()
            ) {
                JSONObject.NULL
            } else {
                value
            }

        fun fmt(value: Double): String = if (value.isNaN()) "?" else "%.1f".format(value)

        fun median(values: List<Double>): Double {
            val sorted = values.filter { !it.isNaN() }.sorted()
            if (sorted.isEmpty()) return Double.NaN
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }

        private fun csvValue(value: Any?): String {
            val s = value?.toString() ?: ""
            return if (s.contains(',') ||
                s.contains('"') ||
                s.contains('\n')
            ) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else {
                s
            }
        }
    }
}
